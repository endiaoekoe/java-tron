package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.NodeList;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Wallet;

@Component
@Slf4j(topic = "API")
public class ListNodesServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      boolean visible = Util.getVisible(request);
      NodeList reply = wallet.listNodes();
      if (reply != null) {
        Histogram.Timer requestTimer = Metrics.histogramStartTimer(
                MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "ListNodes");
        response.getWriter().println(JsonFormat.printToString(reply, visible));
        Metrics.histogramObserve(requestTimer);
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
