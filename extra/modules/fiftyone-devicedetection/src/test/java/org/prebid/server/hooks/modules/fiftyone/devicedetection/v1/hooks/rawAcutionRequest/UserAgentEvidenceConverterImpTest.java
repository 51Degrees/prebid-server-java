package org.prebid.server.hooks.modules.fiftyone.devicedetection.v1.hooks.rawAcutionRequest;

import com.iab.openrtb.request.BrandVersion;
import com.iab.openrtb.request.UserAgent;
import fiftyone.devicedetection.DeviceDetectionOnPremisePipelineBuilder;
import org.junit.Test;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.v1.hooks.FiftyOneDeviceDetectionRawAuctionRequestHook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UserAgentEvidenceConverterImpTest {

    private static BiConsumer<UserAgent, Map<String, String>> buildConverter() throws Exception {

        return new FiftyOneDeviceDetectionRawAuctionRequestHook(null) {
            @Override
            protected DeviceDetectionOnPremisePipelineBuilder makeBuilder() throws Exception {

                final DeviceDetectionOnPremisePipelineBuilder builder
                        = mock(DeviceDetectionOnPremisePipelineBuilder.class);
                when(builder.build()).thenReturn(null);
                return builder;
            }

            @Override
            public void appendSecureHeaders(UserAgent userAgent, Map<String, String> evidence) {

                super.appendSecureHeaders(userAgent, evidence);
            }
        }
            ::appendSecureHeaders;
    }

    @Test
    public void shouldReturnEmptyMapOnEmptyUserAgent() throws Exception {

        // given
        final UserAgent userAgent = UserAgent.builder().build();

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence).isEmpty();
    }

    @Test
    public void shouldAddBrowsers() throws Exception {

        // given
        final UserAgent userAgent = UserAgent.builder()
                .browsers(List.of(
                        new BrandVersion("Nickel", List.of("6", "3", "1", "a"), null),
                        new BrandVersion(null, List.of("7", "52"), null), // should be skipped
                        new BrandVersion("FrostCat", List.of("9", "2", "5", "8"), null)
                ))
                .build();
        final String expectedBrowsers = "\"Nickel\";v=\"6.3.1.a\", \"FrostCat\";v=\"9.2.5.8\"";

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence.size()).isEqualTo(2);
        assertThat(evidence.get("header.Sec-CH-UA")).isEqualTo(expectedBrowsers);
        assertThat(evidence.get("header.Sec-CH-UA-Full-Version-List")).isEqualTo(expectedBrowsers);
    }

    @Test
    public void shouldAddPlatform() throws Exception {

        final UserAgent userAgent = UserAgent.builder()
                .platform(new BrandVersion("Cyborg", List.of("19", "5"), null))
                .build();
        final String expectedPlatformName = "\"Cyborg\"";
        final String expectedPlatformVersion = "\"19.5\"";

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence.size()).isEqualTo(2);
        assertThat(evidence.get("header.Sec-CH-UA-Platform")).isEqualTo(expectedPlatformName);
        assertThat(evidence.get("header.Sec-CH-UA-Platform-Version")).isEqualTo(expectedPlatformVersion);
    }

    @Test
    public void shouldAddIsMobile() throws Exception {

        final UserAgent userAgent = UserAgent.builder()
                .mobile(5)
                .build();
        final String expectedIsMobile = "?5";

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence.size()).isEqualTo(1);
        assertThat(evidence.get("header.Sec-CH-UA-Mobile")).isEqualTo(expectedIsMobile);
    }

    @Test
    public void shouldAddArchitecture() throws Exception {

        final UserAgent userAgent = UserAgent.builder()
                .architecture("LEG")
                .build();
        final String expectedArchitecture = "\"LEG\"";

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence.size()).isEqualTo(1);
        assertThat(evidence.get("header.Sec-CH-UA-Arch")).isEqualTo(expectedArchitecture);
    }

    @Test
    public void shouldAddtBitness() throws Exception {

        final UserAgent userAgent = UserAgent.builder()
                .bitness("doubtful")
                .build();
        final String expectedBitness = "\"doubtful\"";

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence.size()).isEqualTo(1);
        assertThat(evidence.get("header.Sec-CH-UA-Bitness")).isEqualTo(expectedBitness);
    }

    @Test
    public void shouldAddModel() throws Exception {

        final UserAgent userAgent = UserAgent.builder()
                .model("reflectivity")
                .build();
        final String expectedModel = "\"reflectivity\"";

        // when
        final Map<String, String> evidence = new HashMap<>();
        buildConverter().accept(userAgent, evidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence.size()).isEqualTo(1);
        assertThat(evidence.get("header.Sec-CH-UA-Model")).isEqualTo(expectedModel);
    }
}
