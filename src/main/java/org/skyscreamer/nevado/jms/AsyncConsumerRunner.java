package org.skyscreamer.nevado.jms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.skyscreamer.nevado.jms.util.BackoffSleeper;

import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Asynchronous processor for consumers with registered message listeners
 *
 * @author Carter Page <carter@skyscreamer.org>
 */
public class AsyncConsumerRunner implements Runnable {
    private final Log _log = LogFactory.getLog(getClass());
    private final Connection _connection;
    private final Set<NevadoMessageConsumer> _asyncConsumers = new CopyOnWriteArraySet<NevadoMessageConsumer>();
    private volatile boolean _running = false;
    private final BackoffSleeper _sleeper = new BackoffSleeper(10, 15000, 2.0);
    private Thread runner;

    protected AsyncConsumerRunner(Connection connection) {
        _connection = connection;
    }

    public void run() {
        RUN_LOOP: while(_running) {
            boolean messageProcessed = false;
            for(NevadoMessageConsumer consumer : _asyncConsumers)
            {
                messageProcessed = processMessage(consumer) || messageProcessed;
                if (!_running) { break RUN_LOOP; }
            }
            if (messageProcessed == true)
            {
                // If we're getting messages tell the back-off sleeper
                _sleeper.reset();
            }
            _sleeper.sleep();
        }
    }

    public void addAsyncConsumer(NevadoMessageConsumer asyncConsumer)
    {
        _asyncConsumers.add(asyncConsumer);
    }

    public void removeAsyncConsumer(NevadoMessageConsumer asyncConsumer)
    {
        _asyncConsumers.remove(asyncConsumer);
    }

    public int numAsyncConsumers()
    {
        return _asyncConsumers.size();
    }

    private boolean processMessage(NevadoMessageConsumer consumer) {
        boolean messageProcessed = false;
        if (consumer.getMessageListener() != null) {
            try {
                if (consumer.processAsyncMessage()) {
                    messageProcessed = true;
                }
            } catch (JMSException e) {
                _log.error("Unable to process message for consumer on " + consumer.getDestination(), e);
                ExceptionListener exceptionListener = null;
                try {
                    exceptionListener = _connection.getExceptionListener();
                } catch (JMSException e1) {
                    _log.error("Unable to retrieve exception listener from connection", e1);
                }
                if (exceptionListener != null)
                {
                    exceptionListener.onException(e);
                }
            }
        }
        return messageProcessed;
    }

    synchronized void start() {
        if (!_running) {
            runner = new Thread(this);
            //runner.setPriority(Thread.MAX_PRIORITY);
            //runner.setDaemon(true);
            runner.start();
            _running = true;
        }
    }

    synchronized void stop() throws InterruptedException {
        if (_running) {
            _running = false;
            runner.join();
        }
    }
}
