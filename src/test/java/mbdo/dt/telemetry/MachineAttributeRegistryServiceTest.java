package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineAttributeUpdate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineAttributeRegistryServiceTest {

    @Test
    void snapshotsHoldTheLatestValueOfEveryAttribute() {
        MachineAttributeRegistryService registry = new MachineAttributeRegistryService();
        registry.record("CBSystemModel", "conveyorSensFeed", false, Instant.now());
        registry.record("VGRSystemModel_01", "vacuumActValve", true, Instant.now());
        registry.record("CBSystemModel", "conveyorSensFeed", true, Instant.now());

        List<MachineAttributeUpdate> snapshots = registry.snapshots();

        assertEquals(2, snapshots.size());
        assertTrue(snapshots.stream().anyMatch(s -> s.machineId().equals("CBSystemModel")
                && Boolean.TRUE.equals(s.value())), "latest value");
        assertTrue(snapshots.stream().anyMatch(s -> s.machineId().equals("VGRSystemModel_01")
                && s.attribute().equals("vacuumActValve")));
    }

    @Test
    void ignoresMeasurementsWithoutMachineOrAttribute() {
        MachineAttributeRegistryService registry = new MachineAttributeRegistryService();

        assertTrue(registry.record(" ", "conveyorSensFeed", true, Instant.now()).isEmpty());
        assertTrue(registry.record("CBSystemModel", null, true, Instant.now()).isEmpty());
        assertTrue(registry.snapshots().isEmpty());
    }
}
