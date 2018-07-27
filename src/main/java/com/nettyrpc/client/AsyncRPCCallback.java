package com.nettyrpc.client;

/**
 * Created by luxiaoxun on 2016-03-17.
 * 回调处理顶级接口
 */
public interface AsyncRPCCallback {

    void success(Object result);

    void fail(Exception e);

}
