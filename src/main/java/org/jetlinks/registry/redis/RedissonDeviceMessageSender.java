package org.jetlinks.registry.redis;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.device.DeviceMessageSender;
import org.jetlinks.core.device.DeviceOperation;
import org.jetlinks.core.enums.ErrorCode;
import org.jetlinks.core.message.*;
import org.jetlinks.core.message.exception.FunctionUndefinedException;
import org.jetlinks.core.message.exception.ParameterUndefinedException;
import org.jetlinks.core.message.function.FunctionInvokeMessage;
import org.jetlinks.core.message.function.FunctionInvokeMessageReply;
import org.jetlinks.core.message.function.FunctionParameter;
import org.jetlinks.core.message.interceptor.DeviceMessageSenderInterceptor;
import org.jetlinks.core.message.property.ReadPropertyMessage;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.WritePropertyMessage;
import org.jetlinks.core.message.property.WritePropertyMessageReply;
import org.jetlinks.core.metadata.FunctionMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.metadata.ValidateResult;
import org.jetlinks.core.utils.IdUtils;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.jetlinks.core.enums.ErrorCode.NO_REPLY;

/**
 * @author zhouhao
 * @since 1.0.0
 */
@Slf4j
public class RedissonDeviceMessageSender implements DeviceMessageSender {

    private RedissonClient redissonClient;

    private Supplier<String> connectionServerIdSupplier;

    private Runnable deviceStateChecker;

    private String deviceId;

    private DeviceOperation operation;

    @Getter
    @Setter
    private DeviceMessageSenderInterceptor interceptor;

    public RedissonDeviceMessageSender(String deviceId,
                                       RedissonClient redissonClient,
                                       DeviceOperation operation) {
        this.redissonClient = redissonClient;
        this.operation = operation;
        this.connectionServerIdSupplier = operation::getServerId;
        this.deviceStateChecker = operation::checkState;
        this.deviceId = deviceId;
    }

    //最大等待30秒
    @Getter
    @Setter
    private int maxSendAwaitSeconds = Integer.getInteger("device.message.await.max-seconds", 30);

    @SuppressWarnings("all")
    private <R extends DeviceMessageReply> R convertReply(Object deviceReply, RepayableDeviceMessage<R> message, Supplier<R> replyNewInstance) {
        R reply = replyNewInstance.get();
        if (deviceReply == null) {
            reply.error(NO_REPLY);
        } else if (deviceReply instanceof ErrorCode) {
            reply.error((ErrorCode) deviceReply);
        } else {
            if (reply.getClass().isAssignableFrom(deviceReply.getClass())) {
                return reply = (R) deviceReply;
            } else if (deviceReply instanceof String) {
                reply.fromJson(JSON.parseObject(String.valueOf(deviceReply)));
            } else if (deviceReply instanceof DeviceMessage) {
                reply.fromJson(((DeviceMessage) deviceReply).toJson());
            } else {
                reply.error(ErrorCode.UNSUPPORTED_MESSAGE);
                log.warn("不支持的消息类型:{}", deviceReply.getClass());
            }
        }
        if (message != null) {
            reply.from(message);
        }
        return reply;
    }

    public <R extends DeviceMessageReply> CompletionStage<R> retrieveReply(String messageId, Supplier<R> replyNewInstance) {
        return redissonClient
                .getBucket("device:message:reply:".concat(messageId))
                .getAndDeleteAsync()
                .thenApply(deviceReply -> {
                    R reply = convertReply(deviceReply, null, replyNewInstance);
                    reply.messageId(messageId);
                    return reply;
                });
    }

    @Override
    public <R extends DeviceMessageReply> CompletionStage<R> send(DeviceMessage requestMessage, Function<Object, R> replyMapping) {
        String serverId = connectionServerIdSupplier.get();
        //设备当前没有连接到任何服务器
        if (serverId == null) {
            R reply = replyMapping.apply(null);
            if (reply != null) {
                reply.error(ErrorCode.CLIENT_OFFLINE);
            }
            if (null != reply) {
                reply.from(requestMessage);
            }
            return CompletableFuture.completedFuture(reply);
        }
        CompletableFuture<R> future = new CompletableFuture<>();
        if (interceptor != null) {
            requestMessage = interceptor.preSend(operation, requestMessage);
        }
        DeviceMessage message = requestMessage;
        //发送消息给设备当前连接的服务器
        redissonClient
                .getTopic("device:message:accept:".concat(serverId))
                .publishAsync(message)
                .thenCompose(deviceConnectedServerNumber -> {
                    if (deviceConnectedServerNumber <= 0) {
                        //没有任何服务消费此topic,可能所在服务器已经宕机,注册信息没有更新。
                        //执行设备状态检查,尝试更新设备的真实状态
                        if (deviceStateChecker != null) {
                            deviceStateChecker.run();
                        }
                        return CompletableFuture.completedFuture(ErrorCode.CLIENT_OFFLINE);
                    }
                    //有多个相同名称的设备网关服务,可能是服务配置错误,启动了多台相同id的服务。
                    if (deviceConnectedServerNumber > 1) {
                        log.warn("存在多个相同的网关服务:{}", serverId);
                    }
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("发送设备消息[{}]=>{}", message.getDeviceId(), message);
                        }
                        //使用信号量异步等待设备回复通知
                        RSemaphore semaphore = redissonClient.getSemaphore("device:reply:".concat(message.getMessageId()));
                        //设置有效期,防止等待超时或者失败后,信号一直存在于redis中
                        semaphore.expireAsync(maxSendAwaitSeconds + 10, TimeUnit.SECONDS);
                        return semaphore
                                .tryAcquireAsync(deviceConnectedServerNumber.intValue(), maxSendAwaitSeconds, TimeUnit.SECONDS)
                                .thenCompose(complete -> {
                                    try {
                                        if (complete && future.isCancelled()) {
                                            log.debug("设备消息[{}]回复成功,但是已经取消了等待!",
                                                    message.getMessageId());

                                            return CompletableFuture.completedFuture(null);
                                        }
                                        if (!complete) {
                                            log.warn("等待设备消息回复超时,超时时间:{}s,可通过配置:device.message.await.max-seconds进行修改", maxSendAwaitSeconds);
                                        }
                                        return redissonClient
                                                .getBucket("device:message:reply:".concat(message.getMessageId()))
                                                .getAndDeleteAsync()
                                                .whenComplete((r, err) -> {
                                                    if (r != null && log.isDebugEnabled()) {
                                                        log.debug("收到设备回复消息[{}]<={}", message.getDeviceId(), r);
                                                    }
                                                });
                                    } finally {
                                        //删除信号
                                        semaphore.deleteAsync();
                                    }
                                });

                    } catch (Exception e) {
                        //异常直接返回错误
                        log.error(e.getMessage(), e);
                        return CompletableFuture.completedFuture(ErrorCode.SYSTEM_ERROR);
                    }
                })
                .thenApply(replyMapping)
                .thenCompose(r -> {
                    if (interceptor != null) {
                        return interceptor.afterReply(operation, message, r);
                    }
                    return CompletableFuture.completedFuture(r);
                })
                .whenComplete((r, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                    } else {
                        future.complete(r);
                    }
                });

        return future;
    }

    @Override
    @SuppressWarnings("all")
    public <R extends DeviceMessageReply> CompletionStage<R> send(RepayableDeviceMessage<R> message) {
        return send(message, deviceReply -> convertReply(deviceReply, message, message::newReply));
    }

    @Override
    public FunctionInvokeMessageSender invokeFunction(String function) {
        Objects.requireNonNull(function, "function");
        FunctionInvokeMessage message = new FunctionInvokeMessage();
        message.setTimestamp(System.currentTimeMillis());
        message.setDeviceId(deviceId);
        message.setFunctionId(function);
        message.setMessageId(IdUtils.newUUID());
        return new FunctionInvokeMessageSender() {
            @Override
            public FunctionInvokeMessageSender addParameter(String name, Object value) {
                message.addInput(name, value);
                return this;
            }

            @Override
            public FunctionInvokeMessageSender custom(Consumer<FunctionInvokeMessage> messageConsumer) {
                messageConsumer.accept(message);
                return this;
            }

            @Override
            public FunctionInvokeMessageSender messageId(String messageId) {
                message.setMessageId(messageId);
                return this;
            }

            @Override
            public FunctionInvokeMessageSender setParameter(List<FunctionParameter> parameter) {
                message.setInputs(parameter);
                return this;
            }

            @Override
            public FunctionInvokeMessageSender validate(BiConsumer<FunctionParameter, ValidateResult> resultConsumer) {
                //获取功能定义
                FunctionMetadata functionMetadata = operation.getMetadata().getFunction(function)
                        .orElseThrow(() -> new FunctionUndefinedException(function, "功能[" + function + "]未定义"));
                List<PropertyMetadata> metadataInputs = functionMetadata.getInputs();
                List<FunctionParameter> inputs = message.getInputs();

                if (inputs.size() != metadataInputs.size()) {

                    log.warn("调用设备功能[{}]参数数量[需要{},传入{}]错误,功能:{}", function, metadataInputs.size(), inputs.size(), functionMetadata.toString());
                    throw new IllegalArgumentException("参数数量错误");
                }

                //参数定义转为map,避免n*n循环
                Map<String, PropertyMetadata> properties = metadataInputs.stream()
                        .collect(Collectors.toMap(PropertyMetadata::getId, Function.identity(), (t1, t2) -> t1));

                for (FunctionParameter input : message.getInputs()) {
                    PropertyMetadata metadata = Optional.ofNullable(properties.get(input.getName()))
                            .orElseThrow(() -> new ParameterUndefinedException(input.getName(), "参数[" + input.getName() + "]未定义"));
                    resultConsumer.accept(input, metadata.getValueType().validate(input.getValue()));
                }

                return this;
            }

            @Override
            public FunctionInvokeMessageSender header(String header, Object value) {
                message.addHeader(header, value);
                return this;
            }

            @Override
            public FunctionInvokeMessageSender async(Boolean async) {
                message.setAsync(async);
                return this;
            }

            @Override
            public CompletionStage<FunctionInvokeMessageReply> retrieveReply() {
                return RedissonDeviceMessageSender.this.retrieveReply(
                        Objects.requireNonNull(message.getMessageId(), "messageId can not be null"),
                        FunctionInvokeMessageReply::new);
            }

            @Override
            public CompletionStage<FunctionInvokeMessageReply> send() {
                return RedissonDeviceMessageSender.this.send(message);
            }

        };
    }

    @Override
    public ReadPropertyMessageSender readProperty(String... property) {

        ReadPropertyMessage message = new ReadPropertyMessage();
        message.setDeviceId(deviceId);
        message.addProperties(Arrays.asList(property));
        message.setMessageId(IdUtils.newUUID());
        return new ReadPropertyMessageSender() {
            @Override
            public ReadPropertyMessageSender messageId(String messageId) {
                message.setMessageId(messageId);
                return this;
            }

            @Override
            public ReadPropertyMessageSender custom(Consumer<ReadPropertyMessage> messageConsumer) {
                messageConsumer.accept(message);
                return this;
            }

            @Override
            public ReadPropertyMessageSender read(List<String> properties) {
                message.addProperties(properties);
                return this;
            }

            @Override
            public ReadPropertyMessageSender header(String header, Object value) {
                message.addHeader(header, value);
                return this;
            }

            @Override
            public CompletionStage<ReadPropertyMessageReply> retrieveReply() {
                return RedissonDeviceMessageSender.this.retrieveReply(
                        Objects.requireNonNull(message.getMessageId(), "messageId can not be null"),
                        ReadPropertyMessageReply::new);
            }

            @Override
            public CompletionStage<ReadPropertyMessageReply> send() {
                return RedissonDeviceMessageSender.this.send(message);
            }
        };
    }

    @Override
    public WritePropertyMessageSender writeProperty() {

        WritePropertyMessage message = new WritePropertyMessage();
        message.setDeviceId(deviceId);
        message.setMessageId(IdUtils.newUUID());
        return new WritePropertyMessageSender() {
            @Override
            public WritePropertyMessageSender write(String property, Object value) {
                message.addProperty(property, value);
                return this;
            }

            @Override
            public WritePropertyMessageSender custom(Consumer<WritePropertyMessage> messageConsumer) {
                messageConsumer.accept(message);
                return this;
            }

            @Override
            public WritePropertyMessageSender header(String header, Object value) {
                message.addHeader(header, value);
                return this;
            }

            @Override
            public CompletionStage<WritePropertyMessageReply> retrieveReply() {
                return RedissonDeviceMessageSender.this.retrieveReply(
                        Objects.requireNonNull(message.getMessageId(), "messageId can not be null"),
                        WritePropertyMessageReply::new);
            }

            @Override
            public CompletionStage<WritePropertyMessageReply> send() {
                return RedissonDeviceMessageSender.this.send(message);
            }
        };
    }
}
