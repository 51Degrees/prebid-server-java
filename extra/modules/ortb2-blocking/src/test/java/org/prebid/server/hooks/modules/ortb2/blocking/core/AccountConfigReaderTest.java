package org.prebid.server.hooks.modules.ortb2.blocking.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.Audio;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Native;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import org.junit.jupiter.api.Test;
import org.prebid.server.auction.versionconverter.OrtbVersion;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.AllowedForDealsOverride;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.ArrayOverride;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.Attribute;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.AttributeActionOverrides;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.Attributes;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.BooleanOverride;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.Conditions;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.DealsConditions;
import org.prebid.server.hooks.modules.ortb2.blocking.core.config.ModuleConfig;
import org.prebid.server.hooks.modules.ortb2.blocking.core.exception.InvalidAccountConfigurationException;
import org.prebid.server.hooks.modules.ortb2.blocking.core.model.BidAttributeBlockingConfig;
import org.prebid.server.hooks.modules.ortb2.blocking.core.model.BlockedAttributes;
import org.prebid.server.hooks.modules.ortb2.blocking.core.model.ResponseBlockingConfig;
import org.prebid.server.hooks.modules.ortb2.blocking.core.model.Result;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.spring.config.bidder.model.MediaType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AccountConfigReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final OrtbVersion ORTB_VERSION = OrtbVersion.ORTB_2_5;

    @Test
    public void blockedAttributesForShouldReturnEmptyResultWhenNoAccountConfig() {
        // given
        final AccountConfigReader reader = AccountConfigReader.create(null, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(Result.empty());
    }

    @Test
    public void blockedAttributesForShouldReturnEmptyResultWhenNoAttributesField() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(null));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(Result.empty());
    }

    @Test
    public void blockedAttributesForShouldReturnEmptyResultWhenNoBlockedAttributes() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder().build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(Result.empty());
    }

    @Test
    public void blockedAttributesForShouldReturnEmptyResultWhenNoBlockedAdomains() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder().build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(Result.empty());
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenAttributesIsNotObject() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode().put("attributes", 1);
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("attributes field in account configuration is not an object");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBadvIsNotObject() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .put("badv", 1));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("badv field in account configuration is not an object");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBlockedAdomainsIsNotArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .put("blocked-adomain", 1)));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("blocked-adomain field in account configuration is not an array");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBlockedAdomainsIsNotStringArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("blocked-adomain", MAPPER.createArrayNode()
                                        .add(1)
                                        .add("domain2.com"))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("blocked-adomain field in account configuration has unexpected type. "
                        + "Expected class java.lang.String");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBadvActionOverridesIsNotObject() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .put("action-overrides", 1)));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("action-overrides field in account configuration is not an object");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBadvActionOverridesBlockedAdomainIsNotObjectArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(1)
                                                .add(MAPPER.createObjectNode())))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("blocked-adomain field in account configuration is not an array of objects");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenOverridesHasNoConditions() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode())))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("conditions field in account configuration is missing");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenConditionsIsNotObject() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .put("conditions", 1))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("conditions field in account configuration is not an object");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBadvActionOverridesBlockedAdomainConditionsIsEmpty() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .set("conditions", MAPPER.createObjectNode()))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage(
                        "conditions field in account configuration must contain at least one of bidders or media-type");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenConditionBiddersIsNotArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .set("conditions", MAPPER.createObjectNode()
                                                                .put("bidders", 1)))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("bidders field in account configuration is not an array");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenConditionBiddersIsNotStringArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .set("conditions", MAPPER.createObjectNode()
                                                                .set("bidders", MAPPER.createArrayNode()
                                                                        .add(1)
                                                                        .add("abc"))))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("bidders field in account configuration has unexpected type. "
                        + "Expected class java.lang.String");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenConditionMediaTypeIsNotArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .set("conditions", MAPPER.createObjectNode()
                                                                .put("media-type", 1)))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("media-type field in account configuration is not an array");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenConditionMediaTypeIsNotStringArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .set("conditions", MAPPER.createObjectNode()
                                                                .set("media-type", MAPPER.createArrayNode()
                                                                        .add(1)
                                                                        .add("abc"))))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("media-type field in account configuration has unexpected type. "
                        + "Expected class java.lang.String");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenActionOverridesHasNoOverride() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .<ObjectNode>set("conditions", MAPPER.createObjectNode()
                                                                .set("bidders", MAPPER.createArrayNode()
                                                                        .add("bidder1"))))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("override field in account configuration is missing");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBadvBlockedAdomainOverrideIsNotArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .<ObjectNode>set("conditions", MAPPER.createObjectNode()
                                                                .set("bidders", MAPPER.createArrayNode()
                                                                        .add("bidder1")))
                                                        .put("override", 1))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("override field in account configuration is not an array");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBlockedAdomainOverrideIsNotStringArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("blocked-adomain", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .<ObjectNode>set("conditions", MAPPER.createObjectNode()
                                                                .set("bidders", MAPPER.createArrayNode()
                                                                        .add("bidder1")))
                                                        .set("override", MAPPER.createArrayNode()
                                                                .add(1)
                                                                .add("abc")))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(emptyRequest()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("override field in account configuration has unexpected type. "
                        + "Expected class java.lang.String");
    }

    @Test
    public void blockedAttributesForShouldReturnErrorWhenBlockedBannerTypeIsNotIntegerArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("btype", MAPPER.createObjectNode()
                                .set("blocked-banner-type", MAPPER.createArrayNode()
                                        .add(1)
                                        .add("type2"))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.blockedAttributesFor(request(imp -> imp.banner(Banner.builder().build()))))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("blocked-banner-type field in account configuration has unexpected type. "
                        + "Expected class java.lang.Integer");
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithDefaultBadvWhenNoOverrides() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(
                Result.withValue(attributesWithBadv(asList("domain1.com", "domain2.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithDefaultBadvWhenOverridesDoNotMatchRequestByBidder() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder2"), null),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(
                Result.withValue(attributesWithBadv(asList("domain1.com", "domain2.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithDefaultBadvWhenOverridesDoNotMatchRequestByMediaType() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("video")),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(
                Result.withValue(attributesWithBadv(asList("domain1.com", "domain2.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithOverridedBadvWhenOverridesMatchRequest() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(
                Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvFromOverridesWhenMatchRequestByBidderAndMediaType() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(asList("bidder1", "bidder2"), asList("video", "banner")),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        final BidRequest request = BidRequest.builder()
                .imp(asList(
                        Imp.builder().banner(Banner.builder().build()).build(),
                        Imp.builder().xNative(Native.builder().build()).build()))
                .build();

        // when and then
        assertThat(reader.blockedAttributesFor(request))
                .isEqualTo(Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvFromOverridesWhenMatchRequestByBidder() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(emptyRequest())).isEqualTo(
                Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvFromOverridesWhenMatchRequestByMediaType() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(null, singletonList("video")),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.video(Video.builder().build())))).isEqualTo(
                Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvFromOverridesWhenMatchRequestByDifferentMediaTypes() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), asList("audio", "video",
                                                        "banner",
                                                        "native")),
                                                singletonList("domain3.com")))))
                        .build())
                .build()));

        // when and then
        assertThat(AccountConfigReader
                .create(accountConfig, "bidder1", ORTB_VERSION, true)
                .blockedAttributesFor(request(imp -> imp.audio(Audio.builder().build()))))
                .isEqualTo(Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
        assertThat(AccountConfigReader
                .create(accountConfig, "bidder1", ORTB_VERSION, true)
                .blockedAttributesFor(request(imp -> imp.video(Video.builder().build()))))
                .isEqualTo(Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
        assertThat(AccountConfigReader
                .create(accountConfig, "bidder1", ORTB_VERSION, true)
                .blockedAttributesFor(request(imp -> imp.banner(Banner.builder().build()))))
                .isEqualTo(Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
        assertThat(AccountConfigReader
                .create(accountConfig, "bidder1", ORTB_VERSION, true)
                .blockedAttributesFor(request(imp -> imp.xNative(Native.builder().build()))))
                .isEqualTo(Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvAndWarningFromOverridesWhenMultipleSpecificMatches() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                asList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("video")),
                                                singletonList("domain3.com")),
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("banner")),
                                                singletonList("domain4.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader
                .blockedAttributesFor(request(imp -> imp
                        .video(Video.builder().build())
                        .banner(Banner.builder().build()))))
                .isEqualTo(Result.of(
                        attributesWithBadv(singletonList("domain3.com")),
                        singletonList("More than one conditions matches request. Bidder: bidder1, "
                                + "request media types: [banner, video]")));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithoutWarningWhenMultipleSpecificMatchesAndDebugDisabled() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                asList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("video")),
                                                singletonList("domain3.com")),
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("banner")),
                                                singletonList("domain4.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, false);

        // when and then
        assertThat(reader
                .blockedAttributesFor(request(imp -> imp
                        .video(Video.builder().build())
                        .banner(Banner.builder().build()))))
                .isEqualTo(Result.withValue(attributesWithBadv(singletonList("domain3.com"))));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvAndWarningFromOverridesOnMultipleMatches() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                asList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("video")),
                                                singletonList("domain3.com")),
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), singletonList("banner")),
                                                singletonList("domain4.com")),
                                        ArrayOverride.of(
                                                Conditions.of(null, singletonList("video")),
                                                singletonList("domain5.com")),
                                        ArrayOverride.of(
                                                Conditions.of(null, singletonList("banner")),
                                                singletonList("domain6.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader
                .blockedAttributesFor(request(imp -> imp
                        .video(Video.builder().build())
                        .banner(Banner.builder().build()))))
                .isEqualTo(Result.of(
                        attributesWithBadv(singletonList("domain3.com")),
                        singletonList("More than one conditions matches request. Bidder: bidder1, "
                                + "request media types: [banner, video]")));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBadvAndWarningFromOverridesWhenMultipleCatchAllMatches() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(
                                asList(
                                        ArrayOverride.of(
                                                Conditions.of(null, singletonList("video")),
                                                singletonList("domain5.com")),
                                        ArrayOverride.of(
                                                Conditions.of(null, singletonList("banner")),
                                                singletonList("domain6.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader
                .blockedAttributesFor(request(imp -> imp
                        .video(Video.builder().build())
                        .banner(Banner.builder().build()))))
                .isEqualTo(Result.of(
                        attributesWithBadv(singletonList("domain5.com")),
                        singletonList("More than one conditions matches request. Bidder: bidder1, "
                                + "request media types: [banner, video]")));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithBtypeAndWarningsFromOverrides() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .btype(Attribute.btypeBuilder()
                        .actionOverrides(AttributeActionOverrides.blocked(asList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), singletonList("banner")),
                                        singletonList(1)),
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), singletonList("banner")),
                                        singletonList(2)),
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), singletonList("video")),
                                        singletonList(3)),
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), singletonList("video")),
                                        singletonList(4)),
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), singletonList("audio")),
                                        singletonList(5)),
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), singletonList("audio")),
                                        singletonList(6)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        final Map<String, List<Integer>> expectedBtype = new HashMap<>();
        expectedBtype.put("impId1", singletonList(1));
        assertThat(reader
                .blockedAttributesFor(BidRequest.builder()
                        .imp(asList(
                                Imp.builder().id("impId1").banner(Banner.builder().build()).build(),
                                Imp.builder().id("impId2").video(Video.builder().build()).build()))
                        .build()))
                .isEqualTo(Result.of(
                        BlockedAttributes.builder().btype(expectedBtype).build(),
                        List.of("More than one conditions matches request. Bidder: bidder1, "
                                + "request media types: [banner]")));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithAllAttributesForBanner() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                singletonList("domain3.com")))))
                        .build())
                .bcat(Attribute.bcatBuilder()
                        .blocked(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList("cat3")))))
                        .build())
                .bapp(Attribute.bappBuilder()
                        .blocked(asList("app1", "app2"))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList("app3")))))
                        .build())
                .btype(Attribute.btypeBuilder()
                        .blocked(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList(3)))))
                        .build())
                .battr(Attribute.bannerBattrBuilder()
                        .blocked(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList(3)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.id("impId1").banner(Banner.builder().build()))))
                .isEqualTo(Result.withValue(BlockedAttributes.builder()
                        .badv(singletonList("domain3.com"))
                        .bcat(singletonList("cat3"))
                        .bapp(singletonList("app3"))
                        .btype(singletonMap("impId1", singletonList(3)))
                        .battr(singletonMap(MediaType.BANNER, singletonMap("impId1", singletonList(3))))
                        .build()));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithAllAttributesForVideo() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                singletonList("domain3.com")))))
                        .build())
                .bcat(Attribute.bcatBuilder()
                        .blocked(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList("cat3")))))
                        .build())
                .bapp(Attribute.bappBuilder()
                        .blocked(asList("app1", "app2"))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList("app3")))))
                        .build())
                .btype(Attribute.btypeBuilder()
                        .blocked(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList(3)))))
                        .build())
                .battr(Attribute.videoBattrBuilder()
                        .blocked(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList(3)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.id("impId1").video(Video.builder().build()))))
                .isEqualTo(Result.withValue(BlockedAttributes.builder()
                        .badv(singletonList("domain3.com"))
                        .bcat(singletonList("cat3"))
                        .bapp(singletonList("app3"))
                        .battr(singletonMap(MediaType.VIDEO, singletonMap("impId1", singletonList(3))))
                        .build()));
    }

    @Test
    public void blockedAttributesForShouldReturnResultWithAllAttributesForAudio() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .blocked(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.blocked(
                                singletonList(
                                        ArrayOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                singletonList("domain3.com")))))
                        .build())
                .bcat(Attribute.bcatBuilder()
                        .blocked(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList("cat3")))))
                        .build())
                .bapp(Attribute.bappBuilder()
                        .blocked(asList("app1", "app2"))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList("app3")))))
                        .build())
                .btype(Attribute.btypeBuilder()
                        .blocked(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList(3)))))
                        .build())
                .battr(Attribute.audioBattrBuilder()
                        .blocked(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.blocked(singletonList(
                                ArrayOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        singletonList(3)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.id("impId1").audio(Audio.builder().build()))))
                .isEqualTo(Result.withValue(BlockedAttributes.builder()
                        .badv(singletonList("domain3.com"))
                        .bcat(singletonList("cat3"))
                        .bapp(singletonList("app3"))
                        .battr(singletonMap(MediaType.AUDIO, singletonMap("impId1", singletonList(3))))
                        .build()));
    }

    @Test
    public void blockedAttributesForShouldNotReturnCattaxIfBidderSupportsLowerThan26() throws JsonProcessingException {
        // given
        final ObjectNode accountConfig = (ObjectNode) MAPPER.readTree("""
                {
                  "attributes": {
                    "bcat": {
                      "enforce-blocks": true,
                      "block-unknown-adv-cat": false,
                      "category-taxonomy": 6
                    }
                  }
                }
                """);
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.id("impId1")))).isEqualTo(Result.empty());
    }

    @Test
    public void blockedAttributesForShouldReturnCattaxFromRequestIfPresent() throws JsonProcessingException {
        // given
        final ObjectNode accountConfig = (ObjectNode) MAPPER.readTree("""
                {
                  "attributes": {
                    "bcat": {
                      "enforce-blocks": true,
                      "block-unknown-adv-cat": false,
                      "category-taxonomy": 6
                    }
                  }
                }
                """);
        final AccountConfigReader reader = AccountConfigReader.create(
                accountConfig, "bidder1", OrtbVersion.ORTB_2_6, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.id("impId1"), request -> request.cattax(2))))
                .isEqualTo(Result.withValue(BlockedAttributes.builder().cattaxComplement(2).build()));
    }

    @Test
    public void blockedAttributesForShouldReturnCattaxFromConfigIfNotPresentInRequest() throws JsonProcessingException {
        // given
        final ObjectNode accountConfig = (ObjectNode) MAPPER.readTree("""
                {
                  "attributes": {
                    "bcat": {
                      "enforce-blocks": true,
                      "block-unknown-adv-cat": false,
                      "category-taxonomy": 6
                    }
                  }
                }
                """);
        final AccountConfigReader reader = AccountConfigReader.create(
                accountConfig, "bidder1", OrtbVersion.ORTB_2_6, true);

        // when and then
        assertThat(reader.blockedAttributesFor(request(imp -> imp.id("impId1"))))
                .isEqualTo(Result.withValue(BlockedAttributes.builder().cattaxComplement(6).build()));
    }

    @Test
    public void responseBlockingConfigForShouldReturnErrorWhenDefaultEnforceBlocksIsNotBoolean() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .put("enforce-blocks", 1)));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.responseBlockingConfigFor(bid()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("enforce-blocks field in account configuration has unexpected type. "
                        + "Expected class java.lang.Boolean");
    }

    @Test
    public void responseBlockingConfigForShouldReturnErrorWhenDefaultAllowedAdomainIsNotArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .put("allowed-adomain-for-deals", 1)));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.responseBlockingConfigFor(bid()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("allowed-adomain-for-deals field in account configuration is not an array");
    }

    @Test
    public void responseBlockingConfigForShouldReturnErrorWhenDefaultAllowedAdomainIsNotStringArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("allowed-adomain-for-deals", MAPPER.createArrayNode()
                                        .add(1)
                                        .add("domain1.com"))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.responseBlockingConfigFor(bid()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("allowed-adomain-for-deals field in account configuration has unexpected type. "
                        + "Expected class java.lang.String");
    }

    @Test
    public void responseBlockingConfigForShouldReturnErrorWhenDefaultAllowedBlockedAttrIsNotIntegerArray() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("battr", MAPPER.createObjectNode()
                                .set("allowed-banner-attr-for-deals", MAPPER.createArrayNode()
                                        .add(1)
                                        .add("domain1.com"))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.responseBlockingConfigFor(bid()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("allowed-banner-attr-for-deals field in account configuration has unexpected type. "
                        + "Expected class java.lang.Integer");
    }

    @Test
    public void responseBlockingConfigForShouldReturnErrorWhenDealConditionsIsEmpty() {
        // given
        final ObjectNode accountConfig = MAPPER.createObjectNode()
                .set("attributes", MAPPER.createObjectNode()
                        .set("badv", MAPPER.createObjectNode()
                                .set("action-overrides", MAPPER.createObjectNode()
                                        .set("allowed-adomain-for-deals", MAPPER.createArrayNode()
                                                .add(MAPPER.createObjectNode()
                                                        .set("conditions", MAPPER.createObjectNode()))))));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThatThrownBy(() -> reader.responseBlockingConfigFor(bid()))
                .isInstanceOf(InvalidAccountConfigurationException.class)
                .hasMessage("conditions field in account configuration must contain deal-ids");
    }

    @Test
    public void responseBlockingConfigForShouldReturnDefaultResultWhenNoValues() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder().build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(false, false, emptySet()));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithoutDealExceptionWhenNonDealBid() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        final BidderBid bid = BidderBid.of(Bid.builder().build(), BidType.banner, "USD");

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid)).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(false, false, emptySet()));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithDefaultValuesWhenNoOverrides() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(
                    true, true, new HashSet<>(asList("domain1.com", "domain2.com"))));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithDefaultValuesWhenOverridesDoNotMatchRequest() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder2"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder2"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid2")),
                                        singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(
                    true, true, new HashSet<>(asList("domain1.com", "domain2.com"))));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithDefaultAndOverridesWhenOverridesMatchRequest() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        true)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(
                    false, true, new HashSet<>(asList("domain1.com", "domain2.com", "domain3.com"))));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithOverridesWhenOverridesMatchRequest() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        true)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("domain3.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(
                    false, true, new HashSet<>(singletonList("domain3.com"))));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithWarningAndOverrideWhenMultipleMatches() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.blockFlags(
                                asList(
                                        BooleanOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                true),
                                        BooleanOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                false)),
                                asList(
                                        BooleanOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                false),
                                        BooleanOverride.of(
                                                Conditions.of(singletonList("bidder1"), null),
                                                true))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(true, false, emptySet()));
            assertThat(result.getMessages()).containsExactly(
                    "More than one conditions matches request. Bidder: bidder1, request media types: [banner]");
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnResultWithMergedDealExceptionsWhenMultipleMatches() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .actionOverrides(AttributeActionOverrides.allowedForDeals(
                                asList(
                                        AllowedForDealsOverride.of(
                                                DealsConditions.of(asList("dealid1", "dealid2")),
                                                singletonList("domain3.com")),
                                        AllowedForDealsOverride.of(
                                                DealsConditions.of(asList("dealid1", "dealid3")),
                                                singletonList("domain4.com")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isNotNull();
            assertThat(result.getValue().getBadv()).isEqualTo(BidAttributeBlockingConfig.of(
                    false, false, new HashSet<>(asList("domain3.com", "domain4.com"))));
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnAllAttributesForBanner() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("domain3.com")))))
                        .build())
                .bcat(Attribute.bcatBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("cat3")))))
                        .build())
                .bapp(Attribute.bappBuilder()
                        .enforceBlocks(true)
                        .allowedForDeals(asList("app1", "app2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                null,
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("app3")))))
                        .build())
                .battr(Attribute.bannerBattrBuilder()
                        .enforceBlocks(true)
                        .allowedForDeals(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                null,
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList(3)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isEqualTo(ResponseBlockingConfig.builder()
                    .badv(BidAttributeBlockingConfig.of(
                            false, false, Set.of("domain1.com", "domain2.com", "domain3.com")))
                    .bcat(BidAttributeBlockingConfig.of(false, false, Set.of("cat1", "cat2", "cat3")))
                    .cattax(BidAttributeBlockingConfig.of(false, true, emptySet()))
                    .bapp(BidAttributeBlockingConfig.of(false, false, Set.of("app1", "app2", "app3")))
                    .battr(Map.of(
                            MediaType.BANNER, BidAttributeBlockingConfig.of(false, false, Set.of(1, 2, 3)),
                            MediaType.VIDEO, BidAttributeBlockingConfig.of(false, false, emptySet()),
                            MediaType.AUDIO, BidAttributeBlockingConfig.of(false, false, emptySet())))
                    .build());
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnAllAttributesForVideo() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("domain3.com")))))
                        .build())
                .bcat(Attribute.bcatBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("cat3")))))
                        .build())
                .bapp(Attribute.bappBuilder()
                        .enforceBlocks(true)
                        .allowedForDeals(asList("app1", "app2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                null,
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("app3")))))
                        .build())
                .battr(Attribute.videoBattrBuilder()
                        .enforceBlocks(true)
                        .allowedForDeals(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                null,
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList(3)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isEqualTo(ResponseBlockingConfig.builder()
                    .badv(BidAttributeBlockingConfig.of(
                            false, false, Set.of("domain1.com", "domain2.com", "domain3.com")))
                    .bcat(BidAttributeBlockingConfig.of(false, false, Set.of("cat1", "cat2", "cat3")))
                    .cattax(BidAttributeBlockingConfig.of(false, true, emptySet()))
                    .bapp(BidAttributeBlockingConfig.of(false, false, Set.of("app1", "app2", "app3")))
                    .battr(Map.of(
                            MediaType.BANNER, BidAttributeBlockingConfig.of(false, false, emptySet()),
                            MediaType.VIDEO, BidAttributeBlockingConfig.of(false, false, Set.of(1, 2, 3)),
                            MediaType.AUDIO, BidAttributeBlockingConfig.of(false, false, emptySet())))
                    .build());
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnAllAttributesForAudio() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .badv(Attribute.badvBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("domain1.com", "domain2.com"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("domain3.com")))))
                        .build())
                .bcat(Attribute.bcatBuilder()
                        .enforceBlocks(true)
                        .blockUnknown(true)
                        .allowedForDeals(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("cat3")))))
                        .build())
                .bapp(Attribute.bappBuilder()
                        .enforceBlocks(true)
                        .allowedForDeals(asList("app1", "app2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                null,
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("app3")))))
                        .build())
                .battr(Attribute.audioBattrBuilder()
                        .enforceBlocks(true)
                        .allowedForDeals(asList(1, 2))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                null,
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList(3)))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isEqualTo(ResponseBlockingConfig.builder()
                    .badv(BidAttributeBlockingConfig.of(
                            false, false, Set.of("domain1.com", "domain2.com", "domain3.com")))
                    .bcat(BidAttributeBlockingConfig.of(false, false, Set.of("cat1", "cat2", "cat3")))
                    .cattax(BidAttributeBlockingConfig.of(false, true, emptySet()))
                    .bapp(BidAttributeBlockingConfig.of(false, false, Set.of("app1", "app2", "app3")))
                    .battr(Map.of(
                            MediaType.BANNER, BidAttributeBlockingConfig.of(false, false, emptySet()),
                            MediaType.VIDEO, BidAttributeBlockingConfig.of(false, false, emptySet()),
                            MediaType.AUDIO, BidAttributeBlockingConfig.of(false, false, Set.of(1, 2, 3))))
                    .build());
            assertThat(result.getMessages()).isNull();
        });
    }

    @Test
    public void responseBlockingConfigForShouldReturnCattaxConfigDependsOnBcatConfig() {
        // given
        final ObjectNode accountConfig = toObjectNode(ModuleConfig.of(Attributes.builder()
                .bcat(Attribute.bcatBuilder()
                        .enforceBlocks(false)
                        .blockUnknown(true)
                        .allowedForDeals(asList("cat1", "cat2"))
                        .actionOverrides(AttributeActionOverrides.response(
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        true)),
                                singletonList(BooleanOverride.of(
                                        Conditions.of(singletonList("bidder1"), null),
                                        false)),
                                singletonList(AllowedForDealsOverride.of(
                                        DealsConditions.of(singletonList("dealid1")),
                                        singletonList("cat3")))))
                        .build())
                .build()));
        final AccountConfigReader reader = AccountConfigReader.create(accountConfig, "bidder1", ORTB_VERSION, true);

        // when and then
        final Map<MediaType, BidAttributeBlockingConfig<Integer>> expectedBattr = new HashMap<>();
        expectedBattr.put(MediaType.BANNER, null);
        expectedBattr.put(MediaType.VIDEO, null);
        expectedBattr.put(MediaType.AUDIO, null);
        assertThat(reader.responseBlockingConfigFor(bid())).satisfies(result -> {
            assertThat(result.getValue()).isEqualTo(ResponseBlockingConfig.builder()
                    .bcat(BidAttributeBlockingConfig.of(true, false, Set.of("cat1", "cat2", "cat3")))
                    .cattax(BidAttributeBlockingConfig.of(true, true, emptySet()))
                    .battr(expectedBattr)
                    .build());
            assertThat(result.getMessages()).isNull();
        });
    }

    private static BidRequest emptyRequest() {
        return BidRequest.builder()
                .imp(singletonList(Imp.builder().build()))
                .build();
    }

    private static BidRequest request(UnaryOperator<Imp.ImpBuilder> impCustomizer) {
        return BidRequest.builder()
                .imp(singletonList(impCustomizer.apply(Imp.builder()).build()))
                .build();
    }

    private static BidRequest request(UnaryOperator<Imp.ImpBuilder> impCustomizer,
                                      UnaryOperator<BidRequest.BidRequestBuilder> bidRequestCustomizer) {

        return bidRequestCustomizer.apply(BidRequest.builder()
                        .imp(singletonList(impCustomizer.apply(Imp.builder()).build())))
                .build();
    }

    private static BidderBid bid() {
        return BidderBid.of(
                Bid.builder().dealid("dealid1").build(),
                BidType.banner,
                "USD");
    }

    private static BlockedAttributes attributesWithBadv(List<String> badv) {
        return BlockedAttributes.builder().badv(badv).build();
    }

    private static ObjectNode toObjectNode(ModuleConfig config) {
        return MAPPER.valueToTree(config);
    }
}
