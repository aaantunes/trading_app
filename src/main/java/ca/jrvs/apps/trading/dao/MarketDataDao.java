package ca.jrvs.apps.trading.dao;

import ca.jrvs.apps.trading.model.config.MarketDataConfig;
import ca.jrvs.apps.trading.model.domain.IexQuote;
import ca.jrvs.apps.trading.util.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class MarketDataDao {

    private Logger logger = LoggerFactory.getLogger(MarketDataDao.class);

    private final String BATCH_QUOTE_URL;
    private HttpClientConnectionManager httpClientConnectionManager;

    @Autowired
    public MarketDataDao(HttpClientConnectionManager httpClientConnectionManager, MarketDataConfig marketDataConfig) {
        this.httpClientConnectionManager = httpClientConnectionManager;

        BATCH_QUOTE_URL = marketDataConfig.getHost() +
                "/stock/market/batch?symbols=%s&types=quote&token=" +
                marketDataConfig.getToken();
    }

    /**
     * Finds IexQuotes dependent on tickers passed
     *
     * @param tickerList
     * @return List<IexQuote>iexQuotes</IexQuote>
     * @throws org.springframework.dao.DataRetrievalFailureException if unable to get http response
     */
    public List<IexQuote> findIexQuoteByTicker(List<String> tickerList) {
        //convert list into comma seperated string
        String tickerBatch = String.join(",", tickerList);
        String uri = String.format(BATCH_QUOTE_URL, tickerBatch);
        logger.info("Get URI: " + uri);

        //Get HTTP response body in String
        String response = executeHttpGet(uri);
        JSONObject IexQuotesJson = new JSONObject(response);

        if (IexQuotesJson.length() == 0) {
            throw new ResourceNotFoundException("Not found");
        }

        //check for invalid tickers
        if (IexQuotesJson.length() != tickerList.size()) {
            throw new IllegalArgumentException("Invalid ticker/symbol");
        }

        //Unmarhsal JSON object
        List<IexQuote> iexQuotes = new ArrayList<>();
        IexQuotesJson.keys().forEachRemaining(ticker -> {
            try {
                String quoteStr = ((JSONObject) IexQuotesJson.get(ticker)).get("quote").toString();
                IexQuote iexQuote = JsonUtil.toObjectFromJson(quoteStr, IexQuote.class);
                iexQuotes.add(iexQuote);
            } catch (IOException e) {
                throw new DataRetrievalFailureException("Unable to parse response:" + IexQuotesJson.get(ticker), e);
            }
        });

        try {
            logger.info("MarketDataDao/findIexQuoteByTicker iexQuote: " + JsonUtil.toJson(iexQuotes, true, false));
        } catch (JsonProcessingException e) {
            logger.error("logger.error bc of prettyJson");
        }
        return iexQuotes;
    }


    public IexQuote findIexQuoteByTicker(String ticker) {
        List<IexQuote> quotes = findIexQuoteByTicker(Arrays.asList(ticker));
        if (quotes == null || quotes.size() != 1) {
            throw new DataRetrievalFailureException("Unable to get data");
        }
        return quotes.get(0);
    }


    protected String executeHttpGet(String url) {
        try (CloseableHttpClient httpClient = getHttpClient()) {
            HttpGet httpGet = new HttpGet((url));
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                switch (response.getStatusLine().getStatusCode()) {
                    case 200:
                        //EntityUtils toString will also close inputStream in Entity
                        String body = EntityUtils.toString(response.getEntity());
                        return Optional.ofNullable(body).orElseThrow(
                                () -> new IOException("Unexpected empty http response body"));
                    case 404:
                        throw new ResourceNotFoundException("Not found");
                    default:
                        throw new DataRetrievalFailureException("Unexpected status:" + response.getStatusLine().getStatusCode());
                }
            }
        } catch (IOException e) {
            throw new DataRetrievalFailureException("Unable Http execution error", e);
        }
    }

    private CloseableHttpClient getHttpClient() {
        return HttpClients.custom()
                .setConnectionManager(httpClientConnectionManager)
                //prevent connectionManager shutdown when calling httpClient.close()
                .setConnectionManagerShared(true)
                .build();
    }
}
