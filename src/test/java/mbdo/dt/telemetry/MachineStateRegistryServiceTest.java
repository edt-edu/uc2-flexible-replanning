package mbdo.dt.telemetry;

import mbdo.dt.telemetry.dto.MachineStateUpdateResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineStateRegistryServiceTest {

    @Test
    void snapshotsHoldTheLatestStateOfEveryMachine() {
        MachineStateRegistryService registry = new MachineStateRegistryService();
        registry.applyAttribute("CBSystemModel", "conveyorSensFeed", false, Instant.now());
        registry.applyAttribute("VGRSystemModel_01", "vacuumActValve", true, Instant.now());
        registry.applyAttribute("CBSystemModel", "conveyorSensFeed", true, Instant.now());

        List<MachineStateUpdateResponse> snapshots = registry.snapshots();

        assertEquals(2, snapshots.size());
        MachineStateUpdateResponse cb = snapshots.stream()
                .filter(s -> s.getMachineId().equals("CBSystemModel")).findFirst().orElseThrow();
        assertEquals(false, cb.getState().get("sensorFeed"), "latest value, shown as not triggered");
        assertTrue(snapshots.stream().anyMatch(s -> s.getMachineId().equals("VGRSystemModel_01")
                && Boolean.TRUE.equals(s.getState().get("gripperState"))));
    }
}
