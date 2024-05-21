package org.prebid.server.hooks.modules.fiftyone.devicedetection.core.detection.pipelinebuilders;

import fiftyone.devicedetection.DeviceDetectionOnPremisePipelineBuilder;
import org.junit.Test;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.core.detection.PipelineBuilderSpawner;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.core.detection.imps.pipelinebuilders.PipelineBuilderSpawnerImp;
import org.prebid.server.hooks.modules.fiftyone.devicedetection.model.config.DataFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class PipelineBuilderSpawnerImpTest {
    private static PipelineBuilderSpawner<DeviceDetectionOnPremisePipelineBuilder> makeSpawner() {
        return new PipelineBuilderSpawnerImp();
    }
    
    @Test
    public void shouldReturnNonNull() throws Exception {
        // given
        final DataFile dataFile = new DataFile();
        dataFile.setPath("dummy.hash");
        final PipelineBuilderSpawner<DeviceDetectionOnPremisePipelineBuilder> builderSpawner
                = makeSpawner();

        // when
        final DeviceDetectionOnPremisePipelineBuilder pipelineBuilder = builderSpawner.makeBuilder(dataFile);

        // then
        assertThat(pipelineBuilder).isNotNull();
    }
    @Test
    public void shouldReturnNonNullWithCopy() throws Exception {
        // given
        final DataFile dataFile = new DataFile();
        dataFile.setPath("dummy.hash");
        dataFile.setMakeTempCopy(true);
        final PipelineBuilderSpawner<DeviceDetectionOnPremisePipelineBuilder> builderSpawner
                = makeSpawner();

        // when
        final DeviceDetectionOnPremisePipelineBuilder pipelineBuilder = builderSpawner.makeBuilder(dataFile);

        // then
        assertThat(pipelineBuilder).isNotNull();
    }
}
