package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CapePreparationQueueTest {
    @Test
    void repeatedPreparationRetainsOnlyLatestWorkPerRole() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<Integer> completed = new ArrayList<>();
        CapePreparationQueue queue = new CapePreparationQueue(worker::add);
        queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> completed.add(0));
        for (int index = 1; index <= 1000; index++) {
            int value = index;
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> completed.add(value));
        }
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(List.of(0, 1000), completed);
    }

    @Test
    void closeDoesNotDispatchDirtyWork() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<Integer> completed = new ArrayList<>();
        CapePreparationQueue queue = new CapePreparationQueue(worker::add);
        queue.submit(CapePreparationQueue.Slot.OPTIFINE_ADOPTION, () -> completed.add(0));
        queue.submit(CapePreparationQueue.Slot.OPTIFINE_ADOPTION, () -> fail("retired demand"));
        queue.close();
        worker.remove().run();
        assertTrue(worker.isEmpty());
        queue.submit(CapePreparationQueue.Slot.SKINMC_ADOPTION, () -> fail("closed queue"));
        assertTrue(worker.isEmpty());
    }
}
