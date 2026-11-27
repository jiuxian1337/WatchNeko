package cc.watchneko.checks.impl.crash;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentalCrashLimitsTest {
    @Test
    void accountingAllowsTheBudgetAndRecoversAtTheNextWindow() {
        var window = new ExperimentalCrashCheck.RateWindow();
        long next = TimeUnit.SECONDS.toNanos(1);
        assertFalse(window.overLimit(0, 2, 3));
        assertFalse(window.overLimit(10, 1, 3));
        assertTrue(window.overLimit(15, 1, 3));
        assertTrue(window.overLimit(20, 1, 3));
        assertFalse(window.overLimit(next, 3, 3));
        assertTrue(window.overLimit(next + 1, 1, 3));
    }

    @Test
    void registrationRespectsChannelSyntaxAndBatchSize() {
        String registrations = "example:one" + (char) 0 + "example:two";
        assertTrue(CrashO.validRegistration(registrations.getBytes(StandardCharsets.UTF_8), false, 2));
        assertFalse(CrashO.validRegistration(registrations.getBytes(StandardCharsets.UTF_8), false, 1));
        assertTrue(CrashO.validRegistration((registrations + (char) 0).getBytes(StandardCharsets.UTF_8), false, 2));
        assertFalse(CrashO.validRegistration("example".getBytes(StandardCharsets.UTF_8), false, 2));
        assertTrue(CrashO.validRegistration("Example".getBytes(StandardCharsets.UTF_8), true, 2));
    }

    @Test
    void inventoryClickBoundsPreserveTheProtocolSentinels() {
        assertTrue(CrashS.validClickSlot(-999, 46));
        assertTrue(CrashS.validClickSlot(-1, 46));
        assertTrue(CrashS.validClickSlot(45, 46));
        assertFalse(CrashS.validClickSlot(46, 46));
        assertFalse(CrashS.validClickSlot(-2, -1));
        assertTrue(CrashS.validClickSlot(46, -1));
    }

    @Test
    void decodedMetadataIsBoundedByDepthAndByTheSharedPacketBudget() {
        var child = new NBTCompound();
        child.setTag("name", new NBTString("ordinary item"));
        var tag = new NBTCompound();
        tag.setTag("metadata", child);

        var depthLimited = new CrashM.ItemBudget(new CrashM.Limits(8, 4, 8, 1, 128));
        assertFalse(depthLimited.acceptsNbt(tag, 0));

        var packetLimited = new CrashM.ItemBudget(new CrashM.Limits(8, 4, 5, 2, 128));
        assertTrue(packetLimited.acceptsNbt(tag, 0));
        assertFalse(packetLimited.acceptsNbt(tag, 0));
    }

    @Test
    void textBudgetMeasuresMultibyteCharactersAndChildComponents() {
        String text = "汉字🙂";
        var component = Component.text("a").append(Component.text(text));
        int bytes = 1 + text.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(bytes, CrashItemText.size(component, bytes));
        assertTrue(CrashItemText.size(component, bytes - 1) > bytes - 1);
    }
}
