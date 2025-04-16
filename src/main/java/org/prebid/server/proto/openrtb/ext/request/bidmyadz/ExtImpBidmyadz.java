package org.prebid.server.proto.openrtb.ext.request.bidmyadz;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value(staticConstructor = "of")
public class ExtImpBidmyadz {

    @JsonProperty("placementId")
    String placementId;
}
