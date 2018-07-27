package com.nettyrpc.client.proxy;

import com.nettyrpc.client.RPCFuture;

/**
 * Created by luxiaoxun on 2016/3/16.
 * 定义支持异步调用的proxy对象的接口，基础的proxy对象实现了该接口，实现了call方法。
 */
public interface IAsyncObjectProxy {
    public RPCFuture call(String funcName, Object... args);
}