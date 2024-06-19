package org.tron.core.services.http;

import com.google.protobuf.ByteString;
import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.DelegatedResourceList;
import org.tron.api.GrpcAPI.DelegatedResourceMessage;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;



@Component
@Slf4j(topic = "API")
public class GetDelegatedResourceV2Servlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      boolean visible = Util.getVisible(request);
      String fromAddress = request.getParameter("fromAddress");
      String toAddress = request.getParameter("toAddress");
      if (visible) {
        fromAddress = Util.getHexAddress(fromAddress);
        toAddress = Util.getHexAddress(toAddress);
      }
      fillResponse(visible, ByteString.copyFrom(ByteArray.fromHexString(fromAddress)),
          ByteString.copyFrom(ByteArray.fromHexString(toAddress)), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      DelegatedResourceMessage.Builder build = DelegatedResourceMessage.newBuilder();
      JsonFormat.merge(params.getParams(), build, params.isVisible());
      fillResponse(params.isVisible(), build.getFromAddress(), build.getToAddress(), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  private void fillResponse(boolean visible, ByteString fromAddress, ByteString toAddress,
      HttpServletResponse response) throws IOException {
    DelegatedResourceList reply = wallet.getDelegatedResourceV2(fromAddress, toAddress);
    if (reply != null) {
      Histogram.Timer requestTimer = Metrics.histogramStartTimer(
              MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "GetDelegatedResourceV2");
      response.getWriter().println(JsonFormat.printToString(reply, visible));
      Metrics.histogramObserve(requestTimer);
    } else {
      response.getWriter().println("{}");
    }
  }
}
