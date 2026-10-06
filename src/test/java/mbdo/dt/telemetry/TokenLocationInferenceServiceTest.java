package mbdo.dt.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TokenLocationInferenceServiceTest {

    @Test
    void colorIsUnknownUntilTheTokenReachedAColorOutput() {
        assertNull(TokenLocationInferenceService.inferColor(
                List.of("WaitingForTokenAtSortingLineInput", "TokenAtSortingLineInput")));
    }

    @Test
    void colorComesFromTheColorOutputAndFollowsTheToken() {
        assertEquals("RED", TokenLocationInferenceService.inferColor(List.of(
                "WaitingForTokenAtSortingLineInput", "TokenAtSortingLineInput", "TokenAtSortingLineRedOutput",
                "TokenPickedUpByVGR1", "TokenDroppedOffAtConveyorBeltFeed")));
    }

    @Test
    void aNewTokenForgetsThePreviousColor() {
        assertNull(TokenLocationInferenceService.inferColor(List.of(
                "TokenAtSortingLineInput", "TokenAtSortingLineBlueOutput", "TokenAtMPOOutput",
                "WaitingForTokenAtSortingLineInput", "TokenAtSortingLineInput")));
    }
}
