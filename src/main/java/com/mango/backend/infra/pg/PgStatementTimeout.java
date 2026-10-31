package com.mango.backend.infra.pg;

import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;

/** 每次准备语句都向下换算剩余事务时间，避免 JDBC 的 0 秒成为无限等待。 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
        args = {Connection.class, Integer.class}))
public final class PgStatementTimeout implements Interceptor {
    private final DataSource dataSource;

    public PgStatementTimeout(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
        if (holder != null && holder.hasTimeout()) {
            long seconds = holder.getTimeToLiveInMillis() / 1000;
            if (seconds < 1) {
                throw new DependencyException(new Failure(Failure.Code.BUDGET_EXHAUSTED,
                        "数据库剩余预算不足一秒，未开始新查询", false));
            }
            invocation.getArgs()[1] = (int) Math.min(seconds, Integer.MAX_VALUE);
        }
        return invocation.proceed();
    }
}
