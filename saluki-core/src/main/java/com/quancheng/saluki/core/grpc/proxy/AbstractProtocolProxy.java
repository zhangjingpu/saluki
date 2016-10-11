package com.quancheng.saluki.core.grpc.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ObjectUtils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.protobuf.GeneratedMessageV3;
import com.quancheng.saluki.core.common.SalukiConstants;
import com.quancheng.saluki.core.grpc.MethodDescriptorUtils;
import com.quancheng.saluki.core.utils.ReflectUtil;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;

public abstract class AbstractProtocolProxy<T> implements ProtocolProxy<T> {

    private final String                 protocol;

    private final Callable<Channel>      channelCallable;

    private final int                    callType;

    private final int                    rpcTimeout;

    private final boolean                isGeneric;

    private final Class<?>               protocolClzz;

    private final Cache<String, Channel> channelCache;

    protected class JavaProxyInvoker implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            Class<?>[] parameterTypes = method.getParameterTypes();
            if ("toString".equals(methodName) && parameterTypes.length == 0) {
                return proxy.toString();
            }
            if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
                return proxy.hashCode();
            }
            if ("equals".equals(methodName) && parameterTypes.length == 1) {
                return proxy.equals(args[0]);
            }
            GeneratedMessageV3 arg = null;
            if (args.length == 1) {
                // 动态代理覆盖传入的参数
                args = new Object[] { MethodDescriptorUtils.convertPojoToPbModel(args[0]) };
                arg = (GeneratedMessageV3) args[0];
            } else if (args.length == 4) {
                // 泛化调用覆盖第四个参数
                args[3] = new Object[] { MethodDescriptorUtils.convertPojoToPbModel(((Object[]) args[3])[0]) };
                arg = (GeneratedMessageV3) ((Object[]) args[3])[0];
            }
            ClientCall<GeneratedMessageV3, GeneratedMessageV3> newCall = getChannel().newCall(buildMethodDescriptor(method,
                                                                                                                    args),
                                                                                              CallOptions.DEFAULT);
            GeneratedMessageV3 response = null;
            switch (callType) {
                case SalukiConstants.RPCTYPE_ASYNC:
                    response = ClientCalls.futureUnaryCall(newCall, arg).get(rpcTimeout, TimeUnit.SECONDS);
                    break;
                case SalukiConstants.RPCTYPE_BLOCKING:
                    response = ClientCalls.blockingUnaryCall(newCall, arg);
                    break;
                default:
                    response = ClientCalls.futureUnaryCall(newCall, arg).get(rpcTimeout, TimeUnit.SECONDS);
                    break;
            }
            Class<?> returnType = ReflectUtil.getTypeRep(method);
            return MethodDescriptorUtils.convertPbModelToPojo(response, returnType);
        }
    }

    protected abstract MethodDescriptor<GeneratedMessageV3, GeneratedMessageV3> buildMethodDescriptor(Method method,
                                                                                                      Object[] args);

    public AbstractProtocolProxy(String protocol, Callable<Channel> channelCallable, int rpcTimeout, int callType,
                                 boolean isGeneric){
        this.protocol = protocol;
        this.channelCallable = channelCallable;
        this.rpcTimeout = rpcTimeout;
        this.callType = callType;
        this.isGeneric = isGeneric;
        this.protocolClzz = ObjectUtils.defaultIfNull(buildClass(), null);
        this.channelCache = CacheBuilder.newBuilder().maximumSize(1000).build();
    }

    public Channel getChannel() {
        try {
            return channelCache.get(protocol, channelCallable);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public String getProtocol() {
        return protocol;
    }

    public Callable<Channel> getChannelCallable() {
        return channelCallable;
    }

    public int getCallType() {
        return callType;
    }

    public int getRpcTimeout() {
        return rpcTimeout;
    }

    public boolean isGeneric() {
        return isGeneric;
    }

    public Class<?> getProtocolClzz() {
        return protocolClzz;
    }

    public Cache<String, Channel> getChannelCache() {
        return channelCache;
    }

    private Class<?> buildClass() {
        Class<?> protocolClzz = null;
        if (!this.isGeneric()) {
            try {
                protocolClzz = ReflectUtil.name2class(protocol);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        return protocolClzz;
    }

}