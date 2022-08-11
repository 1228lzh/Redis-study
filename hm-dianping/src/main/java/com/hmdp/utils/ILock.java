package com.hmdp.utils;

/**
 * @author king
 */
public interface ILock {

    /**加锁
     *
     * @param timeoutSec 锁持有的超时时间,过期自动释放
     * @return true代表获取锁成功,false代表失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
