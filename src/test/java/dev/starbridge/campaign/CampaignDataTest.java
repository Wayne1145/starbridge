package dev.starbridge.campaign;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CampaignDataTest {
    @Test void crewCanPoolFreightOverRestartsWithoutOverpayingOrRepeatingAContract() {
        CampaignData ledger = new CampaignData();
        assertEquals(1, ledger.deposit(CampaignData.Material.ARRAYS, 1));
        assertEquals(32, ledger.deposit(CampaignData.Material.COPPER, 64));
        ledger = CampaignData.load(ledger.save(new CompoundTag()));
        assertFalse(ledger.online());
        assertEquals(0, ledger.deposit(CampaignData.Material.COPPER, 1));
        assertEquals(2, ledger.deposit(CampaignData.Material.ARRAYS, 64));
        assertEquals(8, ledger.deposit(CampaignData.Material.POWER, 9));
        assertTrue(ledger.online());
        assertEquals(CampaignData.Contract.SUPPLY, ledger.contract());
        ledger.finishContract();
        ledger = CampaignData.load(ledger.save(new CompoundTag()));
        assertEquals(CampaignData.Contract.SURVEY, ledger.contract());
        ledger.finishContract();
        assertEquals(CampaignData.Contract.RESCUE, ledger.contract());
        ledger.finishContract();
        assertEquals(CampaignData.Contract.SUPPLY, ledger.contract());
    }

    @Test void chartsSurviveDeathOrLogoutIndependentlyOfOtherPilotsAndNeverDowngrade() {
        CampaignData ledger = new CampaignData();
        UUID pilot = UUID.randomUUID(), passenger = UUID.randomUUID();
        ledger.learn(pilot, 2);
        ledger.learn(pilot, 1);
        ledger = CampaignData.load(ledger.save(new CompoundTag()));
        assertEquals(2, ledger.rank(pilot));
        assertEquals(0, ledger.rank(passenger));
    }
}
