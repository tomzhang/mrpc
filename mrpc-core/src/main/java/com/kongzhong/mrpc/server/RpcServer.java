package com.kongzhong.mrpc.server;

import com.google.common.util.concurrent.*;
import com.kongzhong.mrpc.annotation.*;
import com.kongzhong.mrpc.common.thread.NamedThreadFactory;
import com.kongzhong.mrpc.common.thread.RpcThreadPool;
import com.kongzhong.mrpc.enums.SerializeEnum;
import com.kongzhong.mrpc.enums.TransferEnum;
import com.kongzhong.mrpc.exception.InitializeException;
import com.kongzhong.mrpc.model.NoInterface;
import com.kongzhong.mrpc.model.RpcRequest;
import com.kongzhong.mrpc.model.RpcResponse;
import com.kongzhong.mrpc.model.ServiceMeta;
import com.kongzhong.mrpc.registry.ServiceRegistry;
import com.kongzhong.mrpc.router.ServiceRouter;
import com.kongzhong.mrpc.spring.utils.AopTargetUtils;
import com.kongzhong.mrpc.transport.TransferSelector;
import com.kongzhong.mrpc.transport.http.HttpResponse;
import com.kongzhong.mrpc.utils.ReflectUtils;
import com.kongzhong.mrpc.utils.StringUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

public class RpcServer implements ApplicationContextAware, InitializingBean {

    public static final Logger log = LoggerFactory.getLogger(RpcServer.class);

    /**
     * 存储服务映射
     */
    private Map<String, Object> handlerMap = new ConcurrentHashMap<>();

    /**
     * rpc服务地址
     */
    private String serverAddress;

    /**
     * 序列化类型，默认protostuff
     */
    private String serialize = SerializeEnum.PROTOSTUFF.name();

    /**
     * 传输协议，默认tcp协议
     */
    private String transfer = TransferEnum.TPC.name();

    /**
     * 服务注册实例
     */
    private ServiceRegistry serviceRegistry;

    /**
     * 传输协议选择
     */
    private TransferSelector transferSelector;

    private static final ListeningExecutorService TPE = MoreExecutors.listeningDecorator((ThreadPoolExecutor) RpcThreadPool.getExecutor(16, -1));

    public static ServiceRouter serviceRouter = new ServiceRouter();

    public RpcServer() {
    }

    public RpcServer(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public RpcServer(String serverAddress, ServiceRegistry serviceRegistry) {
        this.serverAddress = serverAddress;
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * ① 设置上下文
     *
     * @param ctx
     * @throws BeansException
     */
    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        Map<String, Object> serviceBeanMap = ctx.getBeansWithAnnotation(RpcService.class);
        try {
            if (null != serviceBeanMap && !serviceBeanMap.isEmpty()) {
                for (Object serviceBean : serviceBeanMap.values()) {
                    Object realBean = AopTargetUtils.getTarget(serviceBean);
                    RpcService rpcService = realBean.getClass().getAnnotation(RpcService.class);
                    String serviceName = rpcService.value().getName();
                    String version = rpcService.version();
                    String name = rpcService.name();

                    if (StringUtils.isNotEmpty(name)) {
                        serviceName = name;
                    } else {
                        if (NoInterface.class.getName().equals(serviceName)) {
                            Class<?>[] intes = realBean.getClass().getInterfaces();
                            if (null == intes || intes.length != 1) {
                                serviceName = realBean.getClass().getName();
                            } else {
                                serviceName = intes[0].getName();
                            }
                        }
                    }

                    if (StringUtils.isNotEmpty(version)) {
                        serviceName += "_" + version;
                    }

                    handlerMap.put(serviceName, realBean);
                }
            }
            transferSelector = new TransferSelector(handlerMap, serialize);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

    }

    /**
     * ② 后置操作
     *
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        ThreadFactory threadRpcFactory = new NamedThreadFactory("mrpc-server");
        int parallel = Runtime.getRuntime().availableProcessors() * 2;

        EventLoopGroup boss = new NioEventLoopGroup();
        EventLoopGroup worker = new NioEventLoopGroup(parallel, threadRpcFactory, SelectorProvider.provider());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, worker).channel(NioServerSocketChannel.class)
                    .childHandler(transferSelector.getServerChannelHandler(transfer))
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            String[] ipAddr = serverAddress.split(":");

            if (ipAddr.length == 2) {
                //获取服务器IP地址和端口
                String host = ipAddr[0];
                int port = Integer.parseInt(ipAddr[1]);

                ChannelFuture future = bootstrap.bind(host, port).sync();

                if (serviceRegistry != null) {
                    serviceRegistry.register(serverAddress);
                }

                boolean isHttp = transfer.toUpperCase().equals(TransferEnum.HTTP.name());

                //注册服务
                for (String serviceName : handlerMap.keySet()) {
                    log.info("=> [{}] - [{}]", serviceName, serverAddress);
                    if (isHttp) {
                        registerServiceMeta(serviceName);
                    }
                }

                log.info("publish services finished!");
                log.info("mrpc server start with => {}", port);
                future.channel().closeFuture().sync();
            } else {
                log.warn("mrpc server start fail.");
            }
        } finally {
            worker.shutdownGracefully();
            boss.shutdownGracefully();
        }
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public String getSerialize() {
        return serialize;
    }

    public void setSerialize(String serialize) {
        this.serialize = serialize;
    }

    public String getTransfer() {
        return transfer;
    }

    public void setTransfer(String transfer) {
        this.transfer = transfer;
    }

    /**
     * 注册服务元数据
     *
     * @param serviceName
     */
    private void registerServiceMeta(String serviceName) {
        Object bean = handlerMap.get(serviceName);
        Class<?> type = bean.getClass();

        if (ReflectUtils.isImpl(type)) {
            try {
                Class<?> service = ReflectUtils.getInterface(type);
                Path path = service.getAnnotation(Path.class);
                if (null == path) {
                    return;
                }

                String pathUrl = path.value();

                Method[] methods = service.getMethods();
                for (Method m : methods) {
                    String[] methodMapping = getMethodUrl(m);
                    if (null != methodMapping) {
                        String url = ("/" + pathUrl + "/" + methodMapping[1]).replaceAll("[//]+", "/");
                        ServiceMeta serviceMeta = new ServiceMeta(serviceName, m, methodMapping[0]);
                        serviceRouter.add(url, serviceMeta);
                        log.info("register service meta: {}\t{} => {}", methodMapping[0], url, m);
                    }
                }
            } catch (Exception e) {
                throw new InitializeException("register service meta error", e);
            }
        }
    }

    private String[] getMethodUrl(Method m) {
        GET get = m.getAnnotation(GET.class);
        POST post = m.getAnnotation(POST.class);
        DELETE delete = m.getAnnotation(DELETE.class);
        PUT put = m.getAnnotation(PUT.class);
        if (null == get && null == post && null == delete && null == put) {
            return null;
        }

        String[] result = new String[2];

        if (null != get) {
            result[0] = "GET";
            result[1] = (StringUtils.isEmpty(get.value()) ? m.getName() : get.value());
        }
        if (null != post) {
            result[0] = "POST";
            result[1] = (StringUtils.isEmpty(post.value()) ? m.getName() : post.value());
        }
        if (null != delete) {
            result[0] = "DELETE";
            result[1] = (StringUtils.isEmpty(delete.value()) ? m.getName() : delete.value());
        }
        if (null != put) {
            result[0] = "PUT";
            result[1] = (StringUtils.isEmpty(put.value()) ? m.getName() : put.value());
        }

        return result;
    }

    /**
     * 提交任务,异步获取结果.
     *
     * @param task
     * @param ctx
     * @param request
     * @param response
     */
    public static void submit(Callable<Boolean> task, final ChannelHandlerContext ctx, final RpcRequest request, final RpcResponse response) {

        //提交任务, 异步获取结果
        ListenableFuture<Boolean> listenableFuture = TPE.submit(task);

        //注册回调函数, 在task执行完之后 异步调用回调函数
        Futures.addCallback(listenableFuture, new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                //为返回msg回客户端添加一个监听器,当消息成功发送回客户端时被异步调用.
                ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
                    /**
                     * 服务端回显 request已经处理完毕
                     * @param channelFuture
                     * @throws Exception
                     */
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        log.debug("request [{}] success.", request.getRequestId());
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("", t);
            }
        }, TPE);
    }

    public static void submit(Callable<Boolean> task, final ChannelHandlerContext ctx, final RpcRequest request, final HttpResponse response) {

        //提交任务, 异步获取结果
        ListenableFuture<Boolean> listenableFuture = TPE.submit(task);

        //注册回调函数, 在task执行完之后 异步调用回调函数
        Futures.addCallback(listenableFuture, new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                //为返回msg回客户端添加一个监听器,当消息成功发送回客户端时被异步调用.
                ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
                    /**
                     * 服务端回显 request已经处理完毕
                     * @param channelFuture
                     * @throws Exception
                     */
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        log.debug("request [{}] success.", request.getRequestId());
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("", t);
            }
        }, TPE);
    }


}