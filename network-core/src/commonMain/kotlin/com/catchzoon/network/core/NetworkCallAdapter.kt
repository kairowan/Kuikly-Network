package com.catchzoon.network.core

/** 把 NetworkCall 转换成上层框架类型，例如 Android RxJava Single 或自定义任务对象。 */
public fun interface NetworkCallAdapter<T, R> {
    public fun adapt(call: NetworkCall<T>): R
}

/** 使用调用方提供的适配器转换调用，不让核心模块依赖具体响应式框架。 */
public fun <T, R> NetworkCall<T>.adapt(adapter: NetworkCallAdapter<T, R>): R = adapter.adapt(this)
