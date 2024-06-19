package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Wallet;
import org.tron.protos.contract.ShieldContract.IncrementalMerkleVoucherInfo;
import org.tron.protos.contract.ShieldContract.OutputPointInfo;


@Component
@Slf4j(topic = "API")
public class GetMerkleTreeVoucherInfoServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {

  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      OutputPointInfo.Builder build = OutputPointInfo.newBuilder();
      JsonFormat.merge(params.getParams(), build);
      IncrementalMerkleVoucherInfo reply = wallet.getMerkleTreeVoucherInfo(build.build());
      if (reply != null) {
        Histogram.Timer requestTimer = Metrics.histogramStartTimer(
                MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "GetMerkleTreeVoucherInfo");
        response.getWriter().println(JsonFormat.printToString(reply, params.isVisible()));
        Metrics.histogramObserve(requestTimer);
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
