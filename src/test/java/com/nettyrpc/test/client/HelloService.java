package com.nettyrpc.test.client;

/**
 * 这个HelloService会在client端代码中保存一份，代码中直接使用调用方法的形式即可调用。
 * 这里其实是看不到两个方法的具体实现的,因为具体实现不在本系统。
 */
public interface HelloService {
    String hello(String name);

    String hello(Person person);
}
