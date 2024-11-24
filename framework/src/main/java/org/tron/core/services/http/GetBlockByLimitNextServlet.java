package org.tron.core.services.http;

import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.BlockLimit;
import org.tron.api.GrpcAPI.BlockList;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Wallet;
import org.tron.core.config.args.Args;


@Component
@Slf4j(topic = "API")
public class GetBlockByLimitNextServlet extends RateLimiterServlet {

  private static final long BLOCK_LIMIT_NUM = 100;
  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      fillResponse(Util.getVisible(request), Long.parseLong(request.getParameter("startNum")),
          Long.parseLong(request.getParameter("endNum")), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      BlockLimit.Builder build = BlockLimit.newBuilder();
      JsonFormat.merge(params.getParams(), build, params.isVisible());
      fillResponse(params.isVisible(), build.getStartNum(), build.getEndNum(), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  private void fillResponse(boolean visible, long startNum, long endNum,
      HttpServletResponse response)
      throws IOException {

    Map<String,Integer> batchRequestLimit= Args.getInstance().batchRequestLimit;
    Integer getblockbylimitnextMaxSize = batchRequestLimit.get("getblockbylimitnext");
    Long batchSize =endNum - startNum;
    if (endNum > 0 && endNum > startNum && batchSize<= BLOCK_LIMIT_NUM) {
      if(getblockbylimitnextMaxSize!=null){
        if(endNum-startNum>getblockbylimitnextMaxSize){
          batchSize=Long.valueOf(getblockbylimitnextMaxSize);
        }
      }
      BlockList reply = wallet.getBlocksByLimitNext(startNum, batchSize);
      if (reply != null) {
        Histogram.Timer requestTimer = Metrics.histogramStartTimer(
                MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "GetBlockByLimitNext");
        response.getWriter().println(Util.printBlockList(reply, visible));
        Metrics.histogramObserve(requestTimer);
        return;
      }
    }
    response.getWriter().println("{}");
  }
}