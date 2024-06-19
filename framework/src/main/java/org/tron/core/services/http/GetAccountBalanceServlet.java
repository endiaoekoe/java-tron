package org.tron.core.services.http;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Strings;
import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.MetricLabels;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Wallet;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.BalanceContract;


@Component
@Slf4j(topic = "API")
public class GetAccountBalanceServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      BalanceContract.AccountBalanceRequest.Builder builder
          = BalanceContract.AccountBalanceRequest.newBuilder();
      JsonFormat.merge(params.getParams(), builder, params.isVisible());
      fillResponse(params.isVisible(), builder.build(), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  private void fillResponse(boolean visible,
                            BalanceContract.AccountBalanceRequest request,
                            HttpServletResponse response)
      throws Exception {
    BalanceContract.AccountBalanceResponse reply = wallet.getAccountBalance(request);
    if (reply != null) {
      Histogram.Timer requestTimer = Metrics.histogramStartTimer(
              MetricKeys.Histogram.HTTP_RES_DESERIALIZE_LATENCY, "GetAccountBalance");
      response.getWriter().println(JsonFormat.printToString(reply, visible));
      Metrics.histogramObserve(requestTimer);
    } else {
      response.getWriter().println("{}");
    }
  }
}
