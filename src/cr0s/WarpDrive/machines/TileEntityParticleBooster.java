package cr0s.WarpDrive.machines;

import cpw.mods.fml.common.FMLCommonHandler;

import net.minecraft.nbt.NBTTagCompound;
import cr0s.WarpDrive.*;

public class TileEntityParticleBooster extends WarpEnergyTE {
    private int ticks = 0;

    @Override
    public void updateEntity() {
		if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
			return;
		}
		super.updateEntity();

		ticks++;
        if (ticks > 20) {
            ticks = 0;
            
            worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, Math.max(0, Math.min(10, Math.round((getEnergyStored() * 10) / WarpDriveConfig.PB_MAX_ENERGY_VALUE))), 3);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
    }

    // IEnergySink methods implementation
    @Override
    public int getMaxEnergyStored() {
    	return WarpDriveConfig.PB_MAX_ENERGY_VALUE;
    }

    @Override
    public int getMaxSafeInput() {
        return Integer.MAX_VALUE;
    }
}