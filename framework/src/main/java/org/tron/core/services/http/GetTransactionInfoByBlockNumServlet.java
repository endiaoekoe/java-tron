package org.tron.core.services.http;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.NumberMessage;
import org.tron.api.GrpcAPI.TransactionInfoList;
import org.tron.core.Wallet;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.Protocol.TransactionInfo.Log;

@Component
@Slf4j(topic = "API")
public class GetTransactionInfoByBlockNumServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;


  private String printTransactionInfoList(TransactionInfoList list, boolean selfType) {
    JSONArray jsonArray = new JSONArray();
    for (TransactionInfo transactionInfo : list.getTransactionInfoList()) {
      if (visible) {
        List<Log> newLogList = Util.convertLogAddressToTronAddressParallel(transactionInfo);
        transactionInfo = transactionInfo.toBuilder().clearLog().addAllLog(newLogList).build();
      }
      jsonArray.add(transactionInfo);
    }
    return JsonFormat.printToString(jsonArray,selfType);
  }

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      boolean visible = Util.getVisible(request);
      long num = Long.parseLong(request.getParameter("num"));

      if (num > 0L) {
        TransactionInfoList reply = wallet.getTransactionInfoByBlockNum(num);
        response.getWriter().println(printTransactionInfoList(reply, visible));
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      logger.debug("Exception: {}", e.getMessage());
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      NumberMessage.Builder build = NumberMessage.newBuilder();
      JsonFormat.merge(params.getParams(), build, params.isVisible());

      long num = build.getNum();
      if (num > 0L) {
        TransactionInfoList reply = wallet.getTransactionInfoByBlockNum(num);
        response.getWriter().println(printTransactionInfoList(reply, params.isVisible()));
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      logger.debug("Exception: {}", e.getMessage());
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }
}
