package org.prebid.server.hooks.modules.fiftyone.devicedetection.v1.hooks.rawAcutionRequest.detection;

import fiftyone.pipeline.core.data.FlowData;
import fiftyone.pipeline.core.flowelements.Pipeline;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.model.boundary.CollectedEvidence;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.v1.core.DeviceEnricher;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.v1.core.EnrichmentResult;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PriorityEvidenceSelectorImpTest {
    private static Map<String, String> pickRelevantFrom(CollectedEvidence collectedEvidence) throws Exception {
        final Pipeline pipeline = mock(Pipeline.class);
        final FlowData flowData = mock(FlowData.class);
        when(pipeline.createFlowData()).thenReturn(flowData);
        final ArgumentCaptor<Map<String, String>> evidenceCaptor = ArgumentCaptor.forClass(Map.class);
        final DeviceEnricher deviceEnricher = new DeviceEnricher(pipeline);
        final EnrichmentResult result = deviceEnricher.populateDeviceInfo(null, collectedEvidence);
        verify(flowData).addEvidence(evidenceCaptor.capture());
        return evidenceCaptor.getValue();
    }

    @Test
    public void shouldSelectSuaIfPresent() throws Exception {
        // given
        final Map<String, String> secureHeaders = Collections.singletonMap("ua", "fake-ua");
        final CollectedEvidence collectedEvidence = CollectedEvidence.builder()
                .secureHeaders(secureHeaders)
                .rawHeaders(Collections.singletonMap("ua", "zumba").entrySet())
                .build();

        // when
        final Map<String, String> evidence = pickRelevantFrom(collectedEvidence);

        // then
        assertThat(evidence).isNotSameAs(secureHeaders);
        assertThat(evidence).containsExactlyEntriesOf(secureHeaders);
    }

    @Test
    public void shouldSelectUaIfNoSuaPresent() throws Exception {
        // given
        final CollectedEvidence collectedEvidence = CollectedEvidence.builder()
                .deviceUA("dummy-ua")
                .rawHeaders(Collections.singletonMap("ua", "zumba").entrySet())
                .build();

        // when
        final Map<String, String> evidence = pickRelevantFrom(collectedEvidence);

        // then
        assertThat(evidence.size()).isEqualTo(1);
        final Map.Entry<String, String> evidenceFragment = evidence.entrySet().stream().findFirst().get();
        assertThat(evidenceFragment.getKey()).isEqualTo("header.user-agent");
        assertThat(evidenceFragment.getValue()).isEqualTo(collectedEvidence.deviceUA());
    }

    @Test
    public void shouldMergeUaWithSuaIfBothPresent() throws Exception {
        // given
        final Map<String, String> suaHeaders = Collections.singletonMap("ua", "fake-ua");
        final CollectedEvidence collectedEvidence = CollectedEvidence.builder()
                .secureHeaders(suaHeaders)
                .deviceUA("dummy-ua")
                .rawHeaders(Collections.singletonMap("ua", "zumba").entrySet())
                .build();

        // when
        final Map<String, String> evidence = pickRelevantFrom(collectedEvidence);

        // then
        assertThat(evidence).isNotEqualTo(suaHeaders);
        assertThat(evidence).containsAllEntriesOf(suaHeaders);
        assertThat(evidence).containsEntry("header.user-agent", collectedEvidence.deviceUA());
        assertThat(evidence.size()).isEqualTo(suaHeaders.size() + 1);
    }

    @Test
    public void shouldSelectRawHeaderIfNoDeviceInfoPresent() throws Exception {
        // given
        final List<Map.Entry<String, String>> rawHeaders = List.of(
                new AbstractMap.SimpleEntry<>("ua", "zumba"),
                new AbstractMap.SimpleEntry<>("sec-ua", "astrolabe")
        );
        final CollectedEvidence collectedEvidence = CollectedEvidence.builder()
                .rawHeaders(rawHeaders)
                .build();

        // when
        final Map<String, String> evidence = pickRelevantFrom(collectedEvidence);

        // then
        final List<Map.Entry<String, String>> evidenceFragments = evidence.entrySet().stream().toList();
        assertThat(evidenceFragments.size()).isEqualTo(rawHeaders.size());
        for (int i = 0, n = rawHeaders.size(); i < n; ++i) {
            final Map.Entry<String, String> rawEntry = rawHeaders.get(i);
            final Map.Entry<String, String> newEntry = evidenceFragments.get(i);
            assertThat(newEntry.getKey()).isEqualTo("header." + rawEntry.getKey());
            assertThat(newEntry.getValue()).isEqualTo(rawEntry.getValue());
        }
    }

    @Test
    public void shouldPickLastHeaderWithSameKey() throws Exception {
        // given
        final String theKey = "ua";
        final List<Map.Entry<String, String>> rawHeaders = List.of(
                new AbstractMap.SimpleEntry<>(theKey, "zumba"),
                new AbstractMap.SimpleEntry<>(theKey, "astrolabe")
        );
        final CollectedEvidence collectedEvidence = CollectedEvidence.builder()
                .rawHeaders(rawHeaders)
                .build();

        // when
        final Map<String, String> evidence = pickRelevantFrom(collectedEvidence);

        // then
        final List<Map.Entry<String, String>> evidenceFragments = evidence.entrySet().stream().toList();
        assertThat(evidenceFragments.size()).isEqualTo(1);
        assertThat(evidenceFragments.get(0).getValue()).isEqualTo(rawHeaders.get(1).getValue());
    }

    @Test
    public void shouldReturnEmptyMapOnNoEvidenceToPick() throws Exception {
        // given
        final CollectedEvidence collectedEvidence = CollectedEvidence.builder().build();

        // when
        final Map<String, String> evidence = pickRelevantFrom(collectedEvidence);

        // then
        assertThat(evidence).isNotNull();
        assertThat(evidence).isEmpty();
    }
}
