package cr0s.warpdrive.block.collection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Optional;
import cr0s.warpdrive.WarpDrive;
import cr0s.warpdrive.block.TileEntityAbstractLaser;
import cr0s.warpdrive.config.WarpDriveConfig;
import cr0s.warpdrive.data.Vector3;
import cr0s.warpdrive.data.VectorI;
import cr0s.warpdrive.network.PacketHandler;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.peripheral.IComputerAccess;

public class TileEntityMiningLaser extends TileEntityAbstractLaser {
	
	private boolean isMining() {
		return currentState != STATE_IDLE;
	}

	private boolean isQuarry = false;
	private boolean enableSilktouch = false;

	private int delayTicksWarmup = 0;
	private int delayTicksScan = 0;
	private int delayTicksMine = 0;
	private final int STATE_IDLE = 0;
	private final int STATE_WARMUP = 1;
	private final int STATE_SCANNING = 2;
	private final int STATE_MINING = 3;
	private int currentState = 0; // 0 - scan next layer, 1 - collect valuables
	private boolean enoughPower = false;
	private int currentLayer;

	private ArrayList<VectorI> valuablesInLayer = new ArrayList<VectorI>();
	private int valuableIndex = 0;

	private int layerOffset = 1;

	public TileEntityMiningLaser() {
		super();
		peripheralName = "warpdriveMiningLaser";
		addMethods(new String[] {
				"mine",
				"stop",
				"isMining",
				"quarry",
				"state",
				"offset"
		});
		CC_scripts = Arrays.asList("mine", "stop");
		countMaxLaserMediums = WarpDriveConfig.MINING_LASER_MAX_MEDIUMS_COUNT;
	}

	@Override
	public void updateEntity() {
		super.updateEntity();
		
		if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
			return;
		}
		
		if (currentState == STATE_IDLE) {
			delayTicksWarmup = 0;
			delayTicksScan = 0;
			delayTicksMine = 0;
			updateMetadata(BlockMiningLaser.ICON_IDLE);
			return;
		}

		boolean isOnEarth = (worldObj.provider.dimensionId == 0);

		Vector3 laserOutput = new Vector3(xCoord + 0.5D, yCoord, zCoord + 0.5D);

		if (currentState == STATE_WARMUP) { // warming up
			delayTicksWarmup++;
			updateMetadata(BlockMiningLaser.ICON_SCANNINGLOWPOWER);
			if (delayTicksWarmup >= WarpDriveConfig.MINING_LASER_WARMUP_DELAY_TICKS) {
				delayTicksScan = 0;
				currentState = STATE_SCANNING;
				updateMetadata(BlockMiningLaser.ICON_SCANNINGLOWPOWER);
				return;
			}
		} else if (currentState == STATE_SCANNING) { // scanning
			delayTicksScan++;
			if (delayTicksScan == 1) {
				// check power level
				enoughPower = consumeEnergyFromLaserMediums(isOnEarth ? WarpDriveConfig.MINING_LASER_PLANET_ENERGY_PER_LAYER : WarpDriveConfig.MINING_LASER_SPACE_ENERGY_PER_LAYER, true);
				if (!enoughPower) {
					updateMetadata(BlockMiningLaser.ICON_SCANNINGLOWPOWER);
					delayTicksScan = 0;
					return;
				} else {
					updateMetadata(BlockMiningLaser.ICON_SCANNINGPOWERED);
				}
				
				// show current layer
				int age = Math.max(40, 5 * WarpDriveConfig.MINING_LASER_SCAN_DELAY_TICKS);
				double xmax = xCoord + WarpDriveConfig.MINING_LASER_RADIUS_BLOCKS + 1.0D;
				double xmin = xCoord - WarpDriveConfig.MINING_LASER_RADIUS_BLOCKS + 0.0D;
				double zmax = zCoord + WarpDriveConfig.MINING_LASER_RADIUS_BLOCKS + 1.0D;
				double zmin = zCoord - WarpDriveConfig.MINING_LASER_RADIUS_BLOCKS + 0.0D;
				double y = currentLayer + 1.0D;
				PacketHandler.sendBeamPacket(worldObj, new Vector3(xmin, y, zmin), new Vector3(xmax, y, zmin), 0.3F, 0.0F, 1.0F, age, 0, 50);
				PacketHandler.sendBeamPacket(worldObj, new Vector3(xmax, y, zmin), new Vector3(xmax, y, zmax), 0.3F, 0.0F, 1.0F, age, 0, 50);
				PacketHandler.sendBeamPacket(worldObj, new Vector3(xmax, y, zmax), new Vector3(xmin, y, zmax), 0.3F, 0.0F, 1.0F, age, 0, 50);
				PacketHandler.sendBeamPacket(worldObj, new Vector3(xmin, y, zmax), new Vector3(xmin, y, zmin), 0.3F, 0.0F, 1.0F, age, 0, 50);
				
			} else if (delayTicksScan >= WarpDriveConfig.MINING_LASER_SCAN_DELAY_TICKS) {
				delayTicksScan = 0;
				if (currentLayer <= 0) {
					stop();
					return;
				}
				
				// consume power
				enoughPower = consumeEnergyFromLaserMediums(isOnEarth ? WarpDriveConfig.MINING_LASER_PLANET_ENERGY_PER_LAYER : WarpDriveConfig.MINING_LASER_SPACE_ENERGY_PER_LAYER, false);
				if (!enoughPower) {
					updateMetadata(BlockMiningLaser.ICON_SCANNINGLOWPOWER);
					return;
				} else {
					updateMetadata(BlockMiningLaser.ICON_SCANNINGPOWERED);
				}
				
				// scan
				scanLayer();
				if (valuablesInLayer.size() > 0) {
					int r = (int) Math.ceil(WarpDriveConfig.MINING_LASER_RADIUS_BLOCKS / 2.0D);
					int offset = (yCoord - currentLayer) % (2 * r);
					int age = Math.max(20, Math.round(2.5F * WarpDriveConfig.MINING_LASER_SCAN_DELAY_TICKS));
					double y = currentLayer + 1.0D;
					PacketHandler.sendBeamPacket(worldObj, laserOutput, new Vector3(xCoord - r + offset, y, zCoord + r).translate(0.3D),
							0.0F, 0.0F, 1.0F, age, 0, 50);
					PacketHandler.sendBeamPacket(worldObj, laserOutput, new Vector3(xCoord + r, y, zCoord + r - offset).translate(0.3D),
							0.0F, 0.0F, 1.0F, age, 0, 50);
					PacketHandler.sendBeamPacket(worldObj, laserOutput, new Vector3(xCoord + r - offset, y, zCoord - r).translate(0.3D),
							0.0F, 0.0F, 1.0F, age, 0, 50);
					PacketHandler.sendBeamPacket(worldObj, laserOutput, new Vector3(xCoord - r, y, zCoord - r + offset).translate(0.3D),
							0.0F, 0.0F, 1.0F, age, 0, 50);
					worldObj.playSoundEffect(xCoord + 0.5f, yCoord, zCoord + 0.5f, "warpdrive:hilaser", 4F, 1F);
					delayTicksMine = 0;
					currentState = STATE_MINING;
					updateMetadata(BlockMiningLaser.ICON_MININGPOWERED);
					return;
					
				} else {
					worldObj.playSoundEffect(xCoord + 0.5f, yCoord, zCoord + 0.5f, "warpdrive:lowlaser", 4F, 1F);
					currentLayer--;
				}
			}
		} else if (currentState == STATE_MINING) { // mining
			delayTicksMine++;
			if (delayTicksMine >= WarpDriveConfig.MINING_LASER_MINE_DELAY_TICKS) {
				delayTicksMine = 0;
				
				if (valuableIndex >= valuablesInLayer.size()) {
					delayTicksScan = 0;
					currentState = STATE_SCANNING;
					updateMetadata(BlockMiningLaser.ICON_SCANNINGPOWERED);
					
					// rescan same layer
					scanLayer();
					if (valuablesInLayer.size() <= 0) {
						currentLayer--;
					}
					return;
				}
				
				// consume power
				enoughPower = consumeEnergyFromLaserMediums(isOnEarth ? WarpDriveConfig.MINING_LASER_PLANET_ENERGY_PER_BLOCK : WarpDriveConfig.MINING_LASER_SPACE_ENERGY_PER_BLOCK, false);
				if (!enoughPower) {
					updateMetadata(BlockMiningLaser.ICON_MININGLOWPOWER);
					return;
				} else {
					updateMetadata(BlockMiningLaser.ICON_MININGPOWERED);
				}
				
				// System.out.println("[ML] Mining: " + (valuableIndex + 1) + "/" + valuablesInLayer.size());
				VectorI valuable = valuablesInLayer.get(valuableIndex);
				valuableIndex++;
				
				// Mine valuable ore
				Block block = worldObj.getBlock(valuable.x, valuable.y, valuable.z);
				
				// Skip if block is too hard or its empty block (check again in case it changed)
				if (!canDig(block, valuable.x, valuable.y, valuable.z)) {
					delayTicksMine = Math.round(WarpDriveConfig.MINING_LASER_MINE_DELAY_TICKS * 0.8F);
				}
				int age = Math.max(10, Math.round((4 + worldObj.rand.nextFloat()) * WarpDriveConfig.MINING_LASER_MINE_DELAY_TICKS));
				PacketHandler.sendBeamPacket(worldObj, laserOutput, new Vector3(valuable.x, valuable.y, valuable.z).translate(0.5D),
						1.0F, 1.0F, 0.0F, age, 0, 50);
				worldObj.playSoundEffect(xCoord + 0.5f, yCoord, zCoord + 0.5f, "warpdrive:lowlaser", 4F, 1F);
				harvestBlock(valuable);
			}
		}
	}

	private void updateMetadata(int metadata) {
		Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
		if (block.isAssociatedBlock(WarpDrive.blockMiningLaser) && getBlockMetadata() != metadata) {
			worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, metadata, 2);
		}
	}

	private void stop() {
		if (WarpDriveConfig.LOGGING_COLLECTION) {
			WarpDrive.logger.info(this + " Stop requested");
		}
		currentState = STATE_IDLE;
		updateMetadata(BlockMiningLaser.ICON_IDLE);
	}

	private boolean canDig(Block block, int x, int y, int z) {
		// ignore air
		if (worldObj.isAirBlock(x, y, z)) {
			return false;
		}
		// check blacklist
		if (WarpDriveConfig.BLOCKS_NOMINING.contains(block)) {
			stop();
			return false;
		}
		// check whitelist
		if (WarpDriveConfig.BLOCKS_MINING.contains(block) || WarpDriveConfig.BLOCKS_ORES.contains(block)) {
			return true;
		}
		// check default (explosion resistance is used to test for force fields and reinforced blocks, basically preventing mining a base or ship) 
		if (block.getExplosionResistance(null) <= Blocks.obsidian.getExplosionResistance(null)) {
			return true;
		}
		if (WarpDriveConfig.LOGGING_COLLECTION) {
			WarpDrive.logger.info(this + " Rejecting " + block + " at (" + x + ", " + y + ", " + z + ")");
		}
		return false;
	}

	private void harvestBlock(VectorI valuable) {
		Block block = worldObj.getBlock(valuable.x, valuable.y, valuable.z);
		int blockMeta = worldObj.getBlockMetadata(valuable.x, valuable.y, valuable.z);
		if (block != null && (block instanceof BlockLiquid)) {
			// Evaporate fluid
			worldObj.playSoundEffect(valuable.x + 0.5D, valuable.y + 0.5D, valuable.z + 0.5D, "random.fizz", 0.5F,
					2.6F + (worldObj.rand.nextFloat() - worldObj.rand.nextFloat()) * 0.8F);
		} else {
			List<ItemStack> stacks = getItemStackFromBlock(valuable.x, valuable.y, valuable.z, block, blockMeta);
			if (stacks != null) {
				boolean overflow = false;
				int qtyLeft = 0;
				for (ItemStack stack : stacks) {
					qtyLeft = putInChest(findChest(), stack);
					if (qtyLeft > 0) {
						if (WarpDriveConfig.LOGGING_COLLECTION) {
							WarpDrive.logger.info(this + " Overflow detected");
						}
						overflow = true;
						int transfer;
						while (qtyLeft > 0) {
							transfer = Math.min(qtyLeft, stack.getMaxStackSize());
							ItemStack dropItemStack = copyWithSize(stack, transfer);
							EntityItem itemEnt = new EntityItem(worldObj, xCoord + 0.5D, yCoord + 1.0D, zCoord + 0.5D, dropItemStack);
							worldObj.spawnEntityInWorld(itemEnt);
							qtyLeft -= transfer;
						}
					}
				}
				if (overflow) {
					stop();
				}
			}
			// standard harvest block effect
			worldObj.playAuxSFXAtEntity(null, 2001, valuable.x, valuable.y, valuable.z, Block.getIdFromBlock(block) + (blockMeta << 12));
		}
		worldObj.setBlockToAir(valuable.x, valuable.y, valuable.z);
	}

	private IInventory findChest() {
		TileEntity result = null;
		result = worldObj.getTileEntity(xCoord + 1, yCoord, zCoord);
		if (result != null && result instanceof IInventory) {
			return (IInventory) result;
		}

		result = worldObj.getTileEntity(xCoord - 1, yCoord, zCoord);
		if (result != null && result instanceof IInventory) {
			return (IInventory) result;
		}

		result = worldObj.getTileEntity(xCoord, yCoord, zCoord + 1);
		if (result != null && result instanceof IInventory) {
			return (IInventory) result;
		}

		result = worldObj.getTileEntity(xCoord, yCoord, zCoord - 1);
		if (result != null && result instanceof IInventory) {
			return (IInventory) result;
		}

		result = worldObj.getTileEntity(xCoord, yCoord + 1, zCoord);
		if (result != null && result instanceof IInventory) {
			return (IInventory) result;
		}

		return null;
	}

	public List<ItemStack> getItemStackFromBlock(int i, int j, int k, Block block, int blockMeta) {

		if (block == null) {
			return null;
		}
		if (enableSilktouch) {
			boolean isSilkHarvestable = false;
			try {
				isSilkHarvestable = block.canSilkHarvest(worldObj, null, i, j, k, blockMeta);
			} catch (Exception e) {// protect in case the mined block is
				// corrupted
				e.printStackTrace();
			}
			if (isSilkHarvestable) {
				if (WarpDriveConfig.MINING_LASER_SILKTOUCH_DEUTERIUM_L <= 0) {
					ArrayList<ItemStack> isBlock = new ArrayList<ItemStack>();
					isBlock.add(new ItemStack(block, 1, blockMeta));
					return isBlock;
				} else {
					// TODO: implement fluid support through AE or tanks
					WarpDrive.logger.error("Fluids aren't supported yet, ML_DEUTERIUM_MUL_SILKTOUCH should be 0");
				}
			}
		}

		try {
			return block.getDrops(worldObj, i, j, k, blockMeta, 0);
		} catch (Exception e) {// protect in case the mined block is corrupted
			e.printStackTrace();
			return null;
		}
	}

	private int putInChest(IInventory inventory, ItemStack itemStackSource) {
		if (itemStackSource == null) {
			return 0;
		}

		int qtyLeft = itemStackSource.stackSize;
		int transfer;

		if (inventory != null) {
			// fill existing stacks first
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				if (!inventory.isItemValidForSlot(i, itemStackSource)) {
					continue;
				}

				ItemStack itemStack = inventory.getStackInSlot(i);
				if (itemStack == null || !itemStack.isItemEqual(itemStackSource)) {
					continue;
				}

				transfer = Math.min(qtyLeft, itemStack.getMaxStackSize() - itemStack.stackSize);
				itemStack.stackSize += transfer;
				qtyLeft -= transfer;
				if (qtyLeft <= 0) {
					return 0;
				}
			}

			// put remaining in empty slot
			for (int i = 0; i < inventory.getSizeInventory(); i++) {
				if (!inventory.isItemValidForSlot(i, itemStackSource)) {
					continue;
				}

				ItemStack itemStack = inventory.getStackInSlot(i);
				if (itemStack != null) {
					continue;
				}

				transfer = Math.min(qtyLeft, itemStackSource.getMaxStackSize());
				ItemStack dest = copyWithSize(itemStackSource, transfer);
				inventory.setInventorySlotContents(i, dest);
				qtyLeft -= transfer;

				if (qtyLeft <= 0) {
					return 0;
				}
			}
		}

		return qtyLeft;
	}

	public static ItemStack copyWithSize(ItemStack itemStack, int newSize) {
		ItemStack ret = itemStack.copy();
		ret.stackSize = newSize;
		return ret;
	}

	private void scanLayer() {
		// WarpDrive.logger.info("Scanning layer");
		valuablesInLayer.clear();
		valuableIndex = 0;
		int radius, x, z;
		Block block;
		int xmax, zmax;
		int xmin, zmin;

		// Search for valuable blocks
		x = xCoord;
		z = zCoord;
		block = worldObj.getBlock(x, currentLayer, z);
		if (canDig(block, x, currentLayer, z)) {
			if (isQuarry || WarpDriveConfig.BLOCKS_ORES.contains(block)) {// Quarry collects all blocks or only collect valuables blocks
				valuablesInLayer.add(new VectorI(x, currentLayer, z));
			}
		}
		for (radius = 1; radius <= WarpDriveConfig.MINING_LASER_RADIUS_BLOCKS; radius++) {
			xmax = xCoord + radius;
			xmin = xCoord - radius;
			zmax = zCoord + radius;
			zmin = zCoord - radius;
			x = xCoord;
			z = zmin;
			for (; x <= xmax; x++) {
				block = worldObj.getBlock(x, currentLayer, z);
				if (canDig(block, x, currentLayer, z)) {
					if (isQuarry || WarpDriveConfig.BLOCKS_ORES.contains(block)) {// Quarry collects all blocks or only collect valuables blocks
						valuablesInLayer.add(new VectorI(x, currentLayer, z));
					}
				}
			}
			x = xmax;
			z++;
			for (; z <= zmax; z++) {
				block = worldObj.getBlock(x, currentLayer, z);
				if (canDig(block, x, currentLayer, z)) {
					if (isQuarry || WarpDriveConfig.BLOCKS_ORES.contains(block)) {// Quarry collects all blocks or only collect valuables blocks
						valuablesInLayer.add(new VectorI(x, currentLayer, z));
					}
				}
			}
			x--;
			z = zmax;
			for (; x >= xmin; x--) {
				block = worldObj.getBlock(x, currentLayer, z);
				if (canDig(block, x, currentLayer, z)) {
					if (isQuarry || WarpDriveConfig.BLOCKS_ORES.contains(block)) {// Quarry collects all blocks or only collect valuables blocks
						valuablesInLayer.add(new VectorI(x, currentLayer, z));
					}
				}
			}
			x = xmin;
			z--;
			for (; z > zmin; z--) {
				block = worldObj.getBlock(x, currentLayer, z);
				if (canDig(block, x, currentLayer, z)) {
					if (isQuarry || WarpDriveConfig.BLOCKS_ORES.contains(block)) {// Quarry collects all blocks or only collect valuables blocks
						valuablesInLayer.add(new VectorI(x, currentLayer, z));
					}
				}
			}
			x = xmin;
			z = zmin;
			for (; x < xCoord; x++) {
				block = worldObj.getBlock(x, currentLayer, z);
				if (canDig(block, x, currentLayer, z)) {
					if (isQuarry || WarpDriveConfig.BLOCKS_ORES.contains(block)) {// Quarry collects all blocks or only collect valuables blocks
						valuablesInLayer.add(new VectorI(x, currentLayer, z));
					}
				}
			}
		}

		if (WarpDriveConfig.LOGGING_COLLECTION) {
			WarpDrive.logger.info(this + " Found " + valuablesInLayer.size() + " valuables");
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		currentState = tag.getInteger("currentState");
		isQuarry = tag.getBoolean("isQuarry");
		currentLayer = tag.getInteger("currentLayer");
		enableSilktouch = tag.getBoolean("enableSilktouch");
		if (currentState == STATE_MINING) {
			scanLayer();
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);
		tag.setInteger("currentState", currentState);
		tag.setBoolean("isQuarry", isQuarry);
		tag.setInteger("currentLayer", currentLayer);
		tag.setBoolean("enableSilktouch", enableSilktouch);
	}

	// OpenComputer callback methods
	@Callback
	@Optional.Method(modid = "OpenComputers")
	public Object[] mine(Context context, Arguments arguments) {
		return mine(argumentsOCtoCC(arguments));
	}

	@Callback
	@Optional.Method(modid = "OpenComputers")
	public Object[] stop(Context context, Arguments arguments) {
		stop();
		return null;
	}

	@Callback
	@Optional.Method(modid = "OpenComputers")
	public Object[] isMining(Context context, Arguments arguments) {
		return new Boolean[] { isMining() };
	}

	@Callback
	@Optional.Method(modid = "OpenComputers")
	public Object[] quarry(Context context, Arguments arguments) {
		return quarry(argumentsOCtoCC(arguments));
	}

	@Callback
	@Optional.Method(modid = "OpenComputers")
	public Object[] state(Context context, Arguments arguments) {
		return state(argumentsOCtoCC(arguments));
	}

	@Callback
	@Optional.Method(modid = "OpenComputers")
	public Object[] offset(Context context, Arguments arguments) {
		return offset(argumentsOCtoCC(arguments));
	}

	private Object[] mine(Object[] arguments) {
		if (isMining()) {
			return new Boolean[] { false };
		}

		isQuarry = false;
		delayTicksWarmup = 0;
		currentState = STATE_WARMUP;
		currentLayer = yCoord - layerOffset - 1;
		enableSilktouch = (arguments.length == 1 && (WarpDriveConfig.MINING_LASER_SILKTOUCH_DEUTERIUM_L <= 0 || FluidRegistry.isFluidRegistered("deuterium")));
		return new Boolean[] { true };
	}

	private Object[] quarry(Object[] arguments) {
		if (isMining()) {
			return new Boolean[] { false };
		}

		isQuarry = true;
		delayTicksScan = 0;
		currentState = STATE_WARMUP;
		currentLayer = yCoord - layerOffset - 1;
		enableSilktouch = (arguments.length == 1 && (WarpDriveConfig.MINING_LASER_SILKTOUCH_DEUTERIUM_L <= 0 || FluidRegistry.isFluidRegistered("deuterium")));
		return new Boolean[] { true };
	}

	private Object[] state(Object[] arguments) {
		int energy = getEnergyStored();
		String status = getStatus();
		Integer retValuablesInLayer, retValuablesMined;
		if (isMining()) {
			retValuablesInLayer = valuablesInLayer.size();
			retValuablesMined = valuableIndex;

			return new Object[] { status, energy, currentLayer, retValuablesMined, retValuablesInLayer };
		}
		return new Object[] { status, energy, currentLayer, 0, 0 };
	}

	private Object[] offset(Object[] arguments) {
		if (arguments.length == 1) {
			try {
				layerOffset = Math.min(256, Math.abs(toInt(arguments[0])));
			} catch (Exception e) {
				return new Integer[] { layerOffset };
			}
		}
		return new Integer[] { layerOffset };
	}

	// ComputerCraft IPeripheral methods implementation
	@Override
	@Optional.Method(modid = "ComputerCraft")
	public Object[] callMethod(IComputerAccess computer, ILuaContext context, int method, Object[] arguments) {
		String methodName = getMethodName(method);
		
		if (methodName.equals("mine")) {
			return mine(arguments);
			
		} else if (methodName.equals("stop")) {
			stop();
			
		} else if (methodName.equals("isMining")) {
			return new Boolean[] { isMining() };
			
		} else if (methodName.equals("quarry")) {
			return quarry(arguments);
			
		} else if (methodName.equals("state")) { // State is: state, energy,
			// currentLayer, valuablesMined, valuablesInLayer = getMinerState()
			return state(arguments);
			
		} else if (methodName.equals("offset")) {
			return offset(arguments);
		}
		
		return super.callMethod(computer, context, method, arguments);
	}

	public String getStatus() {
		int energy = getEnergyStored();
		String state = "IDLE (not mining)";
		if (currentState == STATE_IDLE) {
			state = "IDLE (not mining)";
		} else if (currentState == STATE_WARMUP) {
			state = "Warming up...";
		} else if (currentState == STATE_SCANNING) {
			if (isQuarry) {
				state = "Scanning all";
			} else {
				state = "Scanning ores";
			}
		} else if (currentState == STATE_MINING) {
			if (isQuarry) {
				state = "Mining all";
			} else {
				state = "Mining ores";
			}
			if (enableSilktouch) {
				state = state + " using Deuterium";
			}
		}
		if (energy <= 0) {
			state = state + " - Out of energy";
		} else if (((currentState == STATE_SCANNING) || (currentState == STATE_MINING)) && !enoughPower) {
			state = state + " - Not enough power";
		}
		return state;
	}
	
	@Override
	public String toString() {
		return String.format("%s @ \'%s\' %d, %d, %d",
				new Object[] { getClass().getSimpleName(), worldObj == null ? "~NULL~" : worldObj.getWorldInfo().getWorldName(), Integer.valueOf(xCoord),
						Integer.valueOf(yCoord), Integer.valueOf(zCoord) });
	}
}
