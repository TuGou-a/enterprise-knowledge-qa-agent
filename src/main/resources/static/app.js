'use strict';

const form = document.querySelector('#question-form');
const questionInput = document.querySelector('#question');
const sendButton = document.querySelector('#send-button');
const sendLabel = document.querySelector('#send-label');
const newChatButton = document.querySelector('#new-chat');
const characterCount = document.querySelector('#character-count');
const welcome = document.querySelector('#welcome');
const conversation = document.querySelector('#conversation');
const messages = document.querySelector('#messages');
const scrollArea = document.querySelector('#scroll-area');
const errorNotice = document.querySelector('#error-notice');
const suggestions = document.querySelectorAll('[data-question]');
const statusLabels = {
  ANSWERED: '已找到依据',
  NEEDS_CLARIFICATION: '需要补充信息',
  CONFLICT: '资料存在冲突',
  NO_EVIDENCE: '暂无充分依据',
  INCOMPLETE: '本次查询未完成'
};

// 只在当前页面保存历史；按后端默认限制保留最近的完整问答轮次。
let history = [];
let turnCount = 0;
let busy = false;

function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function refreshComposer() {
  characterCount.textContent = `${questionInput.value.length} / 2000`;
  sendButton.disabled = busy || !questionInput.value.trim();
  questionInput.disabled = busy;
  newChatButton.disabled = busy;
  suggestions.forEach(button => { button.disabled = busy; });
  sendLabel.textContent = busy ? '查询中' : '发送问题';
}

function scrollToLatest() {
  scrollArea.scrollTop = scrollArea.scrollHeight;
}

function addMessage(role, text) {
  const article = element('article', `message ${role}-message`);
  const author = element('div', 'message-author');
  author.append(element('span', 'message-avatar', role === 'user' ? '我' : '知'));
  author.append(element('span', '', role === 'user' ? '你' : '企业知识助手'));
  const content = element('div', 'message-content');
  if (text) content.append(element('p', 'message-text', text));
  article.append(author, content);
  messages.append(article);
  return { article, content };
}

// HTTP 响应在此边界校验；后续渲染只接收符合契约的回答。
async function requestAnswer(question) {
  if (location.protocol === 'file:') {
    throw new Error('请先启动后端，再通过服务地址访问本页面，例如 http://localhost:8080/。');
  }
  const response = await fetch(new URL('./api/qa', document.baseURI), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ question, history })
  });
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new Error(`问答服务未返回 JSON 数据（HTTP ${response.status}），请确认后端已启动。`);
  }
  const result = await response.json();
  if (!response.ok || result?.code !== 200) {
    throw new Error(typeof result?.message === 'string' ? result.message : `请求失败（HTTP ${response.status}），请稍后重试。`);
  }
  const answer = result.data;
  if (!answer || !Object.hasOwn(statusLabels, answer.status)
      || typeof answer.answer !== 'string'
      || !(answer.clarifyingQuestion === null || typeof answer.clarifyingQuestion === 'string')
      || !Array.isArray(answer.evidenceGaps) || !answer.evidenceGaps.every(gap => typeof gap === 'string')
      || !Array.isArray(answer.citations) || !answer.citations.every(citation => citation
        && ['documentId', 'version', 'chunkId', 'quote'].every(key => typeof citation[key] === 'string')
        && Number.isInteger(citation.startOffset) && Number.isInteger(citation.endOffset))
      || !answer.retrieval || typeof answer.retrieval.degraded !== 'boolean'
      || typeof answer.retrieval.truncated !== 'boolean'
      || !answer.budget || !Number.isFinite(answer.budget.elapsedMillis)) {
    throw new Error('问答服务返回的数据格式有误，请稍后重试。');
  }
  return answer;
}

function renderAnswer(content, answer) {
  content.replaceChildren();
  const header = element('div', 'answer-header');
  const badge = element('span', 'status-badge', statusLabels[answer.status]);
  badge.dataset.status = answer.status;
  header.append(badge, element('span', 'answer-time', `用时 ${(answer.budget.elapsedMillis / 1000).toFixed(1)} 秒`));
  content.append(header, element('p', 'message-text', answer.answer));

  if (answer.clarifyingQuestion) {
    content.append(element('div', 'clarification', `请补充：${answer.clarifyingQuestion}`));
  }
  if (answer.evidenceGaps.length) {
    const gaps = element('div', 'evidence-gaps');
    gaps.append(element('strong', '', '资料缺口'));
    const list = document.createElement('ul');
    answer.evidenceGaps.forEach(gap => list.append(element('li', '', gap)));
    gaps.append(list);
    content.append(gaps);
  }
  if (answer.retrieval.degraded || answer.retrieval.truncated) {
    const notices = [];
    if (answer.retrieval.degraded) notices.push('本次检索存在降级，请结合引用与回答中的限制核对。');
    if (answer.retrieval.truncated) notices.push('本次资料查证存在截断，可能未覆盖全部相关内容。');
    content.append(element('div', 'retrieval-notice', notices.join('\n')));
  }
  if (answer.citations.length) {
    const sources = element('section', 'citations');
    sources.append(element('h2', '', `原文依据 · ${answer.citations.length} 处引用`));
    answer.citations.forEach((citation, index) => {
      const details = element('details', 'citation');
      details.append(element('summary', '', `${index + 1}. ${citation.documentId} · ${citation.version}`));
      details.append(element('blockquote', '', citation.quote));
      details.append(element('p', 'citation-meta', `片段 ${citation.chunkId} · 原文位置 ${citation.startOffset}–${citation.endOffset}`));
      sources.append(details);
    });
    content.append(sources);
  }
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  const question = questionInput.value.trim();
  if (busy || !question) return;
  errorNotice.hidden = true;
  busy = true;
  questionInput.value = '';
  refreshComposer();
  welcome.hidden = true;
  conversation.hidden = false;
  const userMessage = addMessage('user', question);
  const pending = addMessage('assistant');
  pending.content.classList.add('pending-content');
  const spinner = element('span', 'spinner');
  spinner.setAttribute('aria-hidden', 'true');
  pending.content.append(spinner, element('span', '', '正在查阅制度并整理回答，请稍候…'));
  conversation.setAttribute('aria-busy', 'true');
  scrollToLatest();

  try {
    const answer = await requestAnswer(question);
    pending.content.classList.remove('pending-content');
    renderAnswer(pending.content, answer);
    const assistantContent = [answer.answer, answer.clarifyingQuestion, ...answer.evidenceGaps].filter(Boolean).join('\n');
    // 历史只用于理解追问，单条最多 4000 字符；原始完整回答仍显示在页面中。
    history.push({ role: 'user', content: question }, { role: 'assistant', content: assistantContent.slice(0, 4000) || statusLabels[answer.status] });
    while (history.length > 20 || history.reduce((sum, message) => sum + message.content.length, 0) > 12000) {
      history.splice(0, 2);
    }
    turnCount += 1;
    document.querySelector('#turn-count').textContent = `${turnCount} 轮对话`;
  } catch (error) {
    userMessage.article.remove();
    pending.article.remove();
    questionInput.value = question;
    errorNotice.textContent = error instanceof TypeError ? '网络连接失败，请确认后端服务可访问后重新发送。' : error.message;
    errorNotice.hidden = false;
    welcome.hidden = turnCount > 0;
    conversation.hidden = turnCount === 0;
  } finally {
    busy = false;
    conversation.setAttribute('aria-busy', 'false');
    refreshComposer();
    questionInput.focus();
    scrollToLatest();
  }
});

questionInput.addEventListener('input', refreshComposer);
questionInput.addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    if (!sendButton.disabled) form.requestSubmit();
  }
});

suggestions.forEach(button => button.addEventListener('click', () => {
  questionInput.value = button.dataset.question;
  refreshComposer();
  questionInput.focus();
}));

newChatButton.addEventListener('click', () => {
  history = [];
  turnCount = 0;
  messages.replaceChildren();
  document.querySelector('#turn-count').textContent = '当前对话';
  welcome.hidden = false;
  conversation.hidden = true;
  errorNotice.hidden = true;
  questionInput.value = '';
  refreshComposer();
  scrollArea.scrollTop = 0;
  questionInput.focus();
});

refreshComposer();
