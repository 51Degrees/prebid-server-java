package org.prebid.server.it;

import io.restassured.response.Response;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.prebid.server.model.Endpoint;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.Collections.singletonList;

public class SmrtconnectTest extends IntegrationTest {

    @Test
    public void openrtb2AuctionShouldRespondWithBidsFromTheSmrtconnect() throws IOException, JSONException {
        // given
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/smrtconnect-exchange"))
                .withQueryParam("supply_id", equalTo("1"))
                .withRequestBody(equalToJson(jsonFrom("openrtb2/smrtconnect/test-smrtconnect-bid-request.json")))
                .willReturn(aResponse().withBody(jsonFrom("openrtb2/smrtconnect/test-smrtconnect-bid-response.json"))));

        // when
        final Response response = responseFor("openrtb2/smrtconnect/test-auction-smrtconnect-request.json",
                Endpoint.openrtb2_auction);

        // then
        assertJsonEquals("openrtb2/smrtconnect/test-auction-smrtconnect-response.json", response,
                singletonList("smrtconnect"));
    }
}
