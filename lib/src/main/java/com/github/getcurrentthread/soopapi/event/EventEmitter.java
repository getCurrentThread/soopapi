package com.github.getcurrentthread.soopapi.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.exception.EventEmitterException;

public class EventEmitter {
    private static final Logger LOGGER = Logger.getLogger(EventEmitter.class.getName());

    private final Map<ChatEvent, List<EventListener<? extends BaseEvent>>> listeners =
            new ConcurrentHashMap<>();
    private final Map<ChatEvent, List<EventListener<? extends BaseEvent>>> internalListeners =
            new ConcurrentHashMap<>();
    private volatile Consumer<EventEmitterException> errorHandler;

    @SuppressWarnings("unchecked")
    public <T extends BaseEvent> EventEmitter on(ChatEvent event, EventListener<T> listener) {
        listeners
                .computeIfAbsent(event, _ -> new CopyOnWriteArrayList<>())
                .add((EventListener<? extends BaseEvent>) listener);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends BaseEvent> EventEmitter onInternal(
            ChatEvent event, EventListener<T> listener) {
        internalListeners
                .computeIfAbsent(event, _ -> new CopyOnWriteArrayList<>())
                .add((EventListener<? extends BaseEvent>) listener);
        return this;
    }

    /**
     * 이벤트를 한 번만 수신하는 리스너를 등록합니다.
     *
     * <p>여러 스레드에서 동시에 emit되어도 리스너는 정확히 한 번만 호출됩니다. 같은 리스너를 여러 번(또는 여러 이벤트에) 등록하면 등록마다 독립적으로 동작하며,
     * {@link #off(ChatEvent, EventListener)}는 해당 이벤트의 등록을 하나씩 해제합니다. 이벤트가 발생하지 않으면 등록이 남아 있으므로 필요 시
     * {@code off()}나 {@link #clear(ChatEvent)}로 정리하세요.
     */
    public <T extends BaseEvent> EventEmitter once(ChatEvent event, EventListener<T> listener) {
        listeners
                .computeIfAbsent(event, _ -> new CopyOnWriteArrayList<>())
                .add(new OnceListener<>(event, listener));
        return this;
    }

    /** 일반 리스너 또는 {@code once()}로 등록한 리스너를 해제합니다. 한 번 호출에 등록 하나만 해제합니다. */
    public <T extends BaseEvent> EventEmitter off(ChatEvent event, EventListener<T> listener) {
        List<EventListener<? extends BaseEvent>> list = listeners.get(event);
        if (list == null || list.remove(listener)) {
            return this;
        }
        for (EventListener<? extends BaseEvent> registered : list) {
            if (registered instanceof OnceListener<?> once
                    && once.delegate == listener
                    && list.remove(once)) {
                break;
            }
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends BaseEvent> void emit(ChatEvent event, T data) {
        emitFromMap(internalListeners, event, data);
        emitFromMap(listeners, event, data);
    }

    @SuppressWarnings("unchecked")
    private <T extends BaseEvent> void emitFromMap(
            Map<ChatEvent, List<EventListener<? extends BaseEvent>>> map, ChatEvent event, T data) {
        List<EventListener<? extends BaseEvent>> list = map.get(event);
        if (list != null) {
            for (EventListener<? extends BaseEvent> listener : list) {
                try {
                    ((EventListener<T>) listener).onEvent(data);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error executing event listener: " + event, e);
                    Consumer<EventEmitterException> handler = errorHandler;
                    if (handler != null) {
                        try {
                            handler.accept(
                                    new EventEmitterException(
                                            "Error executing event listener: " + event, e, event));
                        } catch (Exception handlerEx) {
                            LOGGER.log(Level.SEVERE, "Error executing error handler", handlerEx);
                        }
                    }
                }
            }
        }
    }

    public void setErrorHandler(Consumer<EventEmitterException> errorHandler) {
        this.errorHandler = errorHandler;
    }

    public void clear() {
        listeners.clear();
    }

    public void clearInternal() {
        internalListeners.clear();
    }

    public void clear(ChatEvent event) {
        listeners.remove(event);
    }

    public boolean hasListeners(ChatEvent event) {
        List<EventListener<? extends BaseEvent>> list = listeners.get(event);
        if (list != null && !list.isEmpty()) {
            return true;
        }
        List<EventListener<? extends BaseEvent>> internal = internalListeners.get(event);
        return internal != null && !internal.isEmpty();
    }

    /** {@code once()} 등록 하나를 나타내는 래퍼. 동시 emit에서도 {@code fired}로 한 번만 위임합니다. */
    private final class OnceListener<T extends BaseEvent> implements EventListener<T> {
        private final ChatEvent event;
        private final EventListener<T> delegate;
        private final AtomicBoolean fired = new AtomicBoolean();

        OnceListener(ChatEvent event, EventListener<T> delegate) {
            this.event = event;
            this.delegate = delegate;
        }

        @Override
        public void onEvent(T e) {
            if (!fired.compareAndSet(false, true)) {
                return;
            }
            List<EventListener<? extends BaseEvent>> list = listeners.get(event);
            if (list != null) {
                list.remove(this);
            }
            delegate.onEvent(e);
        }
    }
}
