package com.github.getcurrentthread.soopapi.api;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** 한 클래스의 로거가 남기는 기록을 모든 레벨로 모은다. 닫으면 로거 설정을 되돌린다. */
final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final boolean previousUseParentHandlers;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler =
            new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };

    LogCapture(Class<?> type) {
        logger = Logger.getLogger(type.getName());
        previousLevel = logger.getLevel();
        previousUseParentHandlers = logger.getUseParentHandlers();
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    int size() {
        return records.size();
    }

    /** 파일 로그에 쓰이는 모양 그대로(첨부된 예외의 스택 트레이스 포함) 이어 붙인 기록. */
    String text() {
        SimpleFormatter formatter = new SimpleFormatter();
        StringBuilder sb = new StringBuilder();
        for (LogRecord record : records) {
            sb.append(formatter.format(record));
        }
        return sb.toString();
    }

    /** 원인 체인까지 포함한 스택 트레이스. */
    static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(previousUseParentHandlers);
        logger.setLevel(previousLevel);
    }
}
