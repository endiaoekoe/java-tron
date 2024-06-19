package org.tron.core.services.http;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.ExchangeList;
import org.tron.api.GrpcAPI.PaginatedMessage;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Wallet;

@Component
@Slf4j(topic = "API")
public class GetPaginatedExchangeListServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      boolean visible = Util.getVisible(request);
      long offset = Long.parseLong(request.getParameter("offset"));
      long limit = Long.parseLong(request.getParameter("limit"));
      fillResponse(offset, limit, visible, response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      PaginatedMessage.Builder build = PaginatedMessage.newBuilder();
      JsonFormat.merge(params.getParams(), build, params.isVisible());
      fillResponse(build.getOffset(), build.getLimit(), params.isVisible(), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  private void fillResponse(long offset, long limit, boolean visible, HttpServletResponse response)
      throws IOException {
    ExchangeList reply = wallet.getPaginatedExchangeList(offset, limit);
    if (reply != null) {
      Histogram.Timer requestTimer = Metrics.histogramStartTimer(
              MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "GetPaginatedExchangeList");
      response.getWriter().println(JsonFormat.printToString(reply, visible));
      Metrics.histogramObserve(requestTimer);
    } else {
      response.getWriter().println("{}");
    }
  }

}
