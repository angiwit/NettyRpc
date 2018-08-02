package com.nettyrpc.client;

/**
 * Created by luxiaoxun on 2016-03-17.
 * 回调处理顶级接口。
 * 如果该框架需要提供很多基本的回调处理方法，都可以通过实现本类来做到。
 * 这个类就是当前框架的顶级回调接口类。
 */
public interface AsyncRPCCallback {

    void success(Object result);

    void fail(Exception e);

}
