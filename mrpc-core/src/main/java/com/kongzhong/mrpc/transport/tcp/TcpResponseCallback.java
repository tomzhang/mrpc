package com.kongzhong.mrpc.transport.tcp;

import com.kongzhong.mrpc.model.RpcContext;
import com.kongzhong.mrpc.model.RpcRequest;
import com.kongzhong.mrpc.model.RpcResponse;
import com.kongzhong.mrpc.transport.SimpleResponseCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tcp响应回调处理
 */
public class TcpResponseCallback extends SimpleResponseCallback<Boolean> {

    public static final Logger log = LoggerFactory.getLogger(TcpResponseCallback.class);

    public TcpResponseCallback(RpcRequest request, RpcResponse response, Map<String, Object> handlerMap) {
        super(request, response, handlerMap);
    }

    @Override
    public Boolean call() throws Exception {
        response.setRequestId(request.getRequestId());
        try {
            Object result = handle(request);
            response.setResult(result);
            return Boolean.TRUE;
        } catch (Exception e) {
            response.setException(e);
            log.error("rpc server invoke error", e);
            return Boolean.FALSE;
        } finally {
            RpcContext.remove();
        }
    }

}