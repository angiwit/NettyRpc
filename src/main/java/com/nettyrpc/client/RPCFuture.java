package com.nettyrpc.client;

import com.nettyrpc.protocol.RpcRequest;
import com.nettyrpc.protocol.RpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RPCFuture for async RPC call
 * Created by luxiaoxun on 2016-03-15.
 * 具体处理：与callback共同处理server端返回后的结果的业务处理（被handler调用）。
 */
public class RPCFuture implements Future<Object> {
    private static final Logger logger = LoggerFactory.getLogger(RPCFuture.class);

    /**
     * 这里sync的作用，是用来做线程间通信使用的。其实就是让调用了future.get()的线程能够在没有数据返回的时候阻塞，有数据返回的时候处理。
     * 其实这个地方也可以使用自己构造的一个队列当做容器，使用生产者消费者的方式处理。
     * 当一个线程acqire的时候，由于可能还没有数据返回，线程会被阻塞掉。然后在release的时候，被阻塞的线程被唤醒(获得执行机会，状态从blocked变到ready)，继续后续操作。
     * release之后的的代码块可以继续执行，唯一的区别就是这个时候可运行的线程多了一个。
     * -1: 当前节点的后续节点需要被唤醒
     * 0: 当前节点在sync队列中，等待着获取锁
     * 1: 当前节点被取消（获取锁超时，或者获取锁时被中断），这个节点需要从sync queue中摘下并通知其后继节点。
     */
    private Sync sync;//线程同步器
    private RpcRequest request;
    private RpcResponse response;
    private long startTime;
    private long responseTimeThreshold = 5000;

    /**
     * 这里是一个RPCFuture中的list，也就是说针对一个请求，可能被添加了多个回调函数，这些回调函数会被保持在该对象的一个list字段中。
     * pendingCallbacks使用非线程安全的arrayList，所以在操作这里面的数据的时候需要获得锁操作
     */
    private List<AsyncRPCCallback> pendingCallbacks = new ArrayList<AsyncRPCCallback>();
    /**
     * 锁的作用是让处理pendingCallbacks这个list的线程同步，跟sync作用没有关系。
     */
    private ReentrantLock lock = new ReentrantLock();

    public RPCFuture(RpcRequest request) {
        this.sync = new Sync();
        this.request = request;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public boolean isDone() {
        return sync.isDone();
    }

    /**
     * 重写future的get方法，直接获取结果。使用future对象都需要重写get方法。
     * 这里的this每个线程都有一个自己的对象
     *
     * 当同步器的状态为1的时候，不会将当前线程加入到queue中排队，否则会将当前线程加入队列中排队，并且中断该线程，导致线程切换（这个时候state的值应该是还是未初始化）
     * 从程序运行角度来说，一定会先运行该句代码（因为一定是先获得锁后释放锁）
     * 第一次运行该代码的时候，一定会把该线程添加到queue中，因为第一次state的值未初始化。添加到queue中之后，线程阻塞，一直等到有数据返回的时候，修改state值为1，并通知到queue中的
     * 线程，开始执行后续代码。
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Override
    public Object get() throws InterruptedException, ExecutionException {
        sync.acquire(-1);//排他的获取锁。获取成功，归还状态（将状态设置为0，0为初始状态）；获取失败，阻塞线程，同时，由于自旋的原因会在下一次时间片来临的时候再次尝试获取锁。
        if (this.response != null) {
            return this.response.getResult();
        } else {
            return null;
        }
    }

    /**
     * 重写了future对象的get方法，可以用超时的方式获取结果。
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        boolean success = sync.tryAcquireNanos(-1, unit.toNanos(timeout));//这里传入什么int值不重要，重要的是tryAcquire的逻辑处理，逻辑里面需要让state满足同步器的变化。
        if (success) {
            if (this.response != null) {
                return this.response.getResult();
            } else {
                return null;
            }
        } else {
            throw new RuntimeException("Timeout exception. Request id: " + this.request.getRequestId()
                    + ". Request class name: " + this.request.getClassName()
                    + ". Request method: " + this.request.getMethodName());
        }
    }

    @Override
    public boolean isCancelled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException();
    }

    /**
     * client端接收到服务器端的返回后，处理返回结果
     *
     * 一个线程获取到了返回结果，然后将同步器释放（归还状态：把state设置为1）。
     * 这个时候在queue中排队的下一个线程会运行acqire，可能获取到锁，如果获取到锁，执行后续代码（这里就是sync.acquire(-1)之后的代码）。
     * 这个时候一定能够通知到一个线程进行后续代码的执行，会首先通知queue中的头结点，头结点失败的情况下从尾结点开始遍历通知。
     *
     * @param reponse
     */
    public void done(RpcResponse reponse) {
        this.response = reponse;
        //这里就是起到一个通知的作用
        sync.release(1);//将state的值设置为1，标识已经有数据进来，这个时候该线程会通知后续节点（acquire这个时候能够获取到锁）。
        invokeCallbacks();
        // Threshold
        long responseTime = System.currentTimeMillis() - startTime;
        if (responseTime > this.responseTimeThreshold) {
            logger.warn("Service response time is too slow. Request id = " + reponse.getRequestId() + ". Response Time = " + responseTime + "ms");
        }
    }

    /**
     * 这里多个callback都是针对一个线程的callback，所以可以共用一个response，但是实际上感觉也不会给一个结果添加多个回调。
     */
    private void invokeCallbacks() {
        lock.lock();//pendingCallbacks使用非线程安全的arrayList，所以在操作这里面的数据的时候需要获得锁操作
        try {
            for (final AsyncRPCCallback callback : pendingCallbacks) {
                runCallback(callback);
            }
        } finally {
            lock.unlock();
        }
    }

    public RPCFuture addCallback(AsyncRPCCallback callback) {
        lock.lock();//pendingCallbacks使用非线程安全的arrayList，所以在操作这里面的数据的时候需要获得锁操作
        try {
            if (isDone()) {//线程判断当前的状态是否为1，如果是1，代表之前已经有数据返回，可以继续后续代码的执行。程序第一次运行到这里的时候state一定不是1，这个时候会直接往pendingCallbacks中添加一个callback
                runCallback(callback);
            } else {
                this.pendingCallbacks.add(callback);
            }
        } finally {
            lock.unlock();
        }
        return this;
    }

    /**
     * 具体实现返回结果处理的逻辑，这里是使用了线程池的方式处理。
     *
     * @param callback
     */
    private void runCallback(final AsyncRPCCallback callback) {
        final RpcResponse res = this.response;
        RpcClient.submit(new Runnable() {
            @Override
            public void run() {
                if (!res.isError()) {
                    callback.success(res.getResult());
                } else {
                    callback.fail(new RuntimeException("Response error", new Throwable(res.getError())));
                }
            }
        });
    }

    static class Sync extends AbstractQueuedSynchronizer {

        private static final long serialVersionUID = 1L;

        //future status
        private final int done = 1;
        private final int pending = 0;

        @Override
        protected boolean tryAcquire(int arg) {
            return getState() == done;
        }

        @Override
        protected boolean tryRelease(int arg) {
            if (getState() == pending) {
                if (compareAndSetState(pending, done)) {
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }

        public boolean isDone() {
            getState();
            return getState() == done;
        }
    }
}
