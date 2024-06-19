package org.tron.core.services.http;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.db.Manager;


@Component
@Slf4j(topic = "API")
public class GetBurnTrxServlet extends RateLimiterServlet {

  @Autowired
  private Manager manager;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      long value = manager.getDynamicPropertiesStore().getBurnTrxAmount();
      Histogram.Timer requestTimer = Metrics.histogramStartTimer(
              MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "GetBurnTrx");
      response.getWriter().println("{\"burnTrxAmount\": " + value + "}");
      Metrics.histogramObserve(requestTimer);
    } catch (Exception e) {
      logger.error("", e);
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
