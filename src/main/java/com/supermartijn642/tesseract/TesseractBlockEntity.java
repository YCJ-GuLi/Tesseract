package com.supermartijn642.tesseract;

import com.supermartijn642.core.block.BaseBlockEntity;
import com.supermartijn642.tesseract.manager.Channel;
import com.supermartijn642.tesseract.manager.TesseractReference;
import com.supermartijn642.tesseract.manager.TesseractTracker;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import team.reborn.energy.api.EnergyStorage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created 3/19/2020 by SuperMartijn642
 */
public class TesseractBlockEntity extends BaseBlockEntity {

    private TesseractReference reference;
    private final Map<EnumChannelType,TransferState> transferState = new EnumMap<>(EnumChannelType.class);
    private final Map<EnumChannelType,Object> capabilities = new EnumMap<>(EnumChannelType.class);
    private RedstoneState redstoneState = RedstoneState.DISABLED;
    private boolean redstone;
    /**
     * Counts recurrent calls inside the combined capabilities in order to prevent infinite loops
     */
    public int recurrentCalls = 0;

    private final Map<Direction,Map<EnumChannelType,BlockApiCache<?,Direction>>> surroundingCapabilities = new HashMap<>();
    private final boolean[] surroundingTesseracts = new boolean[Direction.values().length];

    public TesseractBlockEntity(BlockPos pos, BlockState state){
        super(Tesseract.tesseract_tile, pos, state);
        for(EnumChannelType type : EnumChannelType.values())
            this.transferState.put(type, TransferState.BOTH);
        for(Direction facing : Direction.values())
            this.surroundingCapabilities.put(facing, new EnumMap<>(EnumChannelType.class));
    }

    public TesseractReference getReference(){
        if(this.reference == null)
            this.reference = TesseractTracker.getInstance(this.level).add(this);
        return this.reference;
    }

    public void invalidateReference(){
        this.reference = null;
    }

    public void channelChanged(EnumChannelType type){
        // Clear old capabilities
        this.capabilities.remove(type);
        this.notifyNeighbors();
    }

    public boolean renderOn(){
        return !this.isBlockedByRedstone();
    }

    public Storage<ItemVariant> getItemCapability(){
        //noinspection unchecked
        return (Storage<ItemVariant>)this.capabilities.computeIfAbsent(EnumChannelType.ITEMS, o -> {
            Channel channel = this.getChannel(EnumChannelType.ITEMS);
            return channel == null ? null : channel.getItemHandler(this);
        });
    }

    public Storage<FluidVariant> getFluidCapability(){
        //noinspection unchecked
        return (Storage<FluidVariant>)this.capabilities.computeIfAbsent(EnumChannelType.FLUID, o -> {
            Channel channel = this.getChannel(EnumChannelType.FLUID);
            return channel == null ? null : channel.getFluidHandler(this);
        });
    }

    public EnergyStorage getEnergyCapability(){
        return (EnergyStorage)this.capabilities.computeIfAbsent(EnumChannelType.ENERGY, o -> {
            Channel channel = this.getChannel(EnumChannelType.ENERGY);
            return channel == null ? null : channel.getEnergyStorage(this);
        });
    }

    public List<Storage<ItemVariant>> getSurroundingItemCapabilities(){
        return this.getSurroundingCapabilities(EnumChannelType.ITEMS, ItemStorage.SIDED);
    }

    public List<Storage<FluidVariant>> getSurroundingFluidCapabilities(){
        return this.getSurroundingCapabilities(EnumChannelType.FLUID, FluidStorage.SIDED);
    }

    public List<EnergyStorage> getSurroundingEnergyCapabilities(){
        return this.getSurroundingCapabilities(EnumChannelType.ENERGY, EnergyStorage.SIDED);
    }

    private <T> List<T> getSurroundingCapabilities(EnumChannelType type, BlockApiLookup<T,Direction> api){
        if(this.level == null)
            return Collections.emptyList();

        //noinspection unchecked
        return (List<T>)Arrays.stream(Direction.values())
            .filter(side -> !this.surroundingTesseracts[side.ordinal()])
            .map(side -> this.surroundingCapabilities.get(side).computeIfAbsent(type, t -> BlockApiCache.create(api, (ServerLevel)this.level, this.worldPosition.relative(side))).find(side))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    public boolean canSend(EnumChannelType type){
        return this.transferState.get(type).canSend() && !this.isBlockedByRedstone();
    }

    public boolean canReceive(EnumChannelType type){
        return this.transferState.get(type).canReceive() && !this.isBlockedByRedstone();
    }

    public boolean isBlockedByRedstone(){
        return this.redstoneState != RedstoneState.DISABLED && this.redstoneState == (this.redstone ? RedstoneState.LOW : RedstoneState.HIGH);
    }

    public int getChannelId(EnumChannelType type){
        return this.getReference().getChannelId(type);
    }

    public TransferState getTransferState(EnumChannelType type){
        return this.transferState.get(type);
    }

    public void cycleTransferState(EnumChannelType type){
        TransferState transferState = this.transferState.get(type);
        this.transferState.put(type, transferState == TransferState.BOTH ? TransferState.SEND : transferState == TransferState.SEND ? TransferState.RECEIVE : TransferState.BOTH);
        this.updateReference();
        this.dataChanged();
    }

    public RedstoneState getRedstoneState(){
        return this.redstoneState;
    }

    public void cycleRedstoneState(){
        this.redstoneState = this.redstoneState == RedstoneState.DISABLED ? RedstoneState.HIGH : this.redstoneState == RedstoneState.HIGH ? RedstoneState.LOW : RedstoneState.DISABLED;
        this.updateReference();
        this.dataChanged();
    }

    public void setPowered(boolean powered){
        if(this.redstone != powered){
            this.redstone = powered;
            this.updateReference();
            this.dataChanged();
        }
    }

    private Channel getChannel(EnumChannelType type){
        return this.getReference().getChannel(type);
    }

    public void onNeighborChanged(BlockPos neighbor){
        Direction side = Direction.fromDelta(neighbor.getX() - this.worldPosition.getX(), neighbor.getY() - this.worldPosition.getY(), neighbor.getZ() - this.worldPosition.getZ());
        BlockState state = this.level.getBlockState(neighbor);
        this.surroundingTesseracts[side.ordinal()] = state.getBlock() == Tesseract.tesseract;
    }

    private void notifyNeighbors(){
        this.level.blockUpdated(this.worldPosition, this.getBlockState().getBlock());
    }

    private void updateReference(){
        TesseractReference reference = this.getReference();
        if(reference != null)
            reference.update(this);
    }

    @Override
    protected CompoundTag writeData(){
        CompoundTag compound = new CompoundTag();
        for(EnumChannelType type : EnumChannelType.values())
            compound.putString("transferState" + type.name(), this.transferState.get(type).name());
        compound.putString("redstoneState", this.redstoneState.name());
        compound.putBoolean("powered", this.redstone);
        return compound;
    }

    @Override
    protected void readData(CompoundTag compound){
        for(EnumChannelType type : EnumChannelType.values())
            if(compound.contains("transferState" + type.name()))
                this.transferState.put(type, TransferState.valueOf(compound.getString("transferState" + type.name())));
        if(compound.contains("redstoneState"))
            this.redstoneState = RedstoneState.valueOf(compound.getString("redstoneState"));
        if(compound.contains("powered"))
            this.redstone = compound.getBoolean("powered");
    }

    public void onReplaced(){
        if(!this.level.isClientSide)
            TesseractTracker.SERVER.remove(this.level, this.worldPosition);
    }
}
