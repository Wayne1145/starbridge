package dev.starbridge.compat.computer;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachedComputersTest {
    @Test void aComputerUsingTwoConnectionsRemainsVisibleUntilBothDetach() {
        var links = new AttachedComputers();
        links.attach(17);
        links.attach(17);
        links.detach(17);
        assertTrue(links.contains(17));
        assertEquals(List.of(17), links.page(0).ids());
        links.detach(17);
        assertFalse(links.contains(17));
        assertTrue(links.page(0).ids().isEmpty());
    }

    @Test void stalePageAfterDisconnectionsIsClampedToTheLastLiveDevice() {
        var links = new AttachedComputers();
        for (int id : new int[] {8, 0, 22, 1, 7, 11, 10}) links.attach(id);
        assertEquals(List.of(0, 1, 7), links.page(-1).ids());
        assertEquals(2, links.pageOf(22));
        assertEquals(List.of(22), links.page(2).ids());
        links.detach(22);
        assertEquals(new AttachedComputers.Page(1, 2, List.of(8, 10, 11)), links.page(Integer.MAX_VALUE));
    }
}
