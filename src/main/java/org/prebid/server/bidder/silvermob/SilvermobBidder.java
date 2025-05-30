package org.prebid.server.bidder.silvermob;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.silvermob.ExtImpSilvermob;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class SilvermobBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpSilvermob>> SILVERMOB_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };

    private static final String URL_HOST_MACRO = "{{Host}}";
    private static final String URL_ZONE_ID_MACRO = "{{ZoneID}}";

    private final String endpointUrl;
    private final JacksonMapper mapper;

    public SilvermobBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();
        final List<HttpRequest<BidRequest>> requests = new ArrayList<>();

        for (Imp imp : request.getImp()) {
            try {
                requests.add(createRequestForImp(imp, request));
            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }

        return Result.of(requests, errors);
    }

    private HttpRequest<BidRequest> createRequestForImp(Imp imp, BidRequest request) {
        final ExtImpSilvermob extImp = parseImpExt(imp);
        if (isInvalidHost(extImp.getHost())) {
            throw new PreBidException(String.format("Invalid host: %s", extImp.getHost()));
        }

        final BidRequest outgoingRequest = request.toBuilder()
                .imp(Collections.singletonList(imp))
                .build();
        return HttpRequest.<BidRequest>builder()
                .method(HttpMethod.POST)
                .uri(resolveEndpoint(extImp))
                .headers(resolveHeaders(request.getDevice()))
                .payload(outgoingRequest)
                .body(mapper.encodeToBytes(outgoingRequest))
                .build();
    }

    private ExtImpSilvermob parseImpExt(Imp imp) {
        final ExtImpSilvermob extImp;
        try {
            extImp = mapper.mapper().convertValue(imp.getExt(), SILVERMOB_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException("error unmarshalling imp.ext.bidder: " + e.getMessage());
        }
        if (StringUtils.isBlank(extImp.getHost())) {
            throw new PreBidException("host is a required silvermob ext.imp param");
        }
        if (StringUtils.isBlank(extImp.getZoneId())) {
            throw new PreBidException("zoneId is a required silvermob ext.imp param");
        }
        return extImp;
    }

    private static Boolean isInvalidHost(String host) {
        return !StringUtils.equalsAny(host, "eu", "us", "apac", "global");
    }

    private String resolveEndpoint(ExtImpSilvermob extImp) {
        return endpointUrl
                .replace(URL_HOST_MACRO, extImp.getHost())
                .replace(URL_ZONE_ID_MACRO, HttpUtil.encodeUrl(extImp.getZoneId()));
    }

    private static MultiMap resolveHeaders(Device device) {
        final MultiMap headers = HttpUtil.headers()
                .add(HttpUtil.X_OPENRTB_VERSION_HEADER, "2.5");

        if (device != null) {
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.USER_AGENT_HEADER, device.getUa());
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.X_FORWARDED_FOR_HEADER, device.getIpv6());
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.X_FORWARDED_FOR_HEADER, device.getIp());
        }

        return headers;
    }

    @Override
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            return Result.of(extractBids(httpCall), Collections.emptyList());
        } catch (DecodeException | PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidderCall<BidRequest> httpCall) {
        final BidResponse bidResponse;
        try {
            bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
        } catch (DecodeException e) {
            throw new PreBidException("Error unmarshalling server Response: " + e.getMessage());
        }
        if (bidResponse == null) {
            throw new PreBidException("Response in not present");
        }
        if (CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            throw new PreBidException("Empty SeatBid array");
        }
        return bidsFromResponse(bidResponse);
    }

    private static List<BidderBid> bidsFromResponse(BidResponse bidResponse) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> BidderBid.of(bid, getBidType(bid), bidResponse.getCur()))
                .toList();
    }

    private static BidType getBidType(Bid bid) {
        final Integer markupType = bid.getMtype();
        if (markupType == null) {
            throw new PreBidException("Missing MType for bid: " + bid.getId());
        }

        return switch (markupType) {
            case 1 -> BidType.banner;
            case 2 -> BidType.video;
            case 4 -> BidType.xNative;
            default -> throw new PreBidException("Unable to fetch mediaType in multi-format: %s"
                    .formatted(bid.getImpid()));
        };
    }
}
