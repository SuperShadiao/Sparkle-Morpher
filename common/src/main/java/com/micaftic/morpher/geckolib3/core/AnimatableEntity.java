package com.micaftic.morpher.geckolib3.core;

import com.google.common.collect.ImmutableList;
import com.micaftic.morpher.capability.PlayerCapability;
import com.micaftic.morpher.client.animation.debug.AnimationFrameProfiler;
import com.micaftic.morpher.client.render.RiderRotationMath;
import com.micaftic.morpher.config.GeneralConfig;
import com.micaftic.morpher.audio.IAudioStreamFactory;
import com.micaftic.morpher.client.event.ClientTickEvent;
import com.micaftic.morpher.geckolib3.core.enums.AnimationState;
import com.micaftic.morpher.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.micaftic.morpher.geckolib3.core.builder.AnimationController;
import com.micaftic.morpher.geckolib3.core.manager.AnimationData;
import com.micaftic.morpher.geckolib3.core.controller.IAnimationController;
import com.micaftic.morpher.client.animation.molang.PhysicsManager;
import com.micaftic.morpher.client.renderer.ModelPreviewRenderer;
import com.micaftic.morpher.core.api.entity.EntityDataBridge;
import com.micaftic.morpher.geckolib3.core.builder.Animation;
import com.micaftic.morpher.geckolib3.core.event.predicate.AnimationEvent;
import com.micaftic.morpher.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.micaftic.morpher.geckolib3.core.molang.value.IValue;
import com.micaftic.morpher.geckolib3.core.processor.AnimationProcessor;
import com.micaftic.morpher.geckolib3.core.processor.IBone;
import com.micaftic.morpher.geckolib3.model.provider.data.EntityModelData;
import com.micaftic.morpher.client.entity.IPreviewAnimatable;
import com.micaftic.morpher.geckolib3.core.molang.context.AnimationContext;
import com.micaftic.morpher.geckolib3.core.util.RateLimiter;
import com.micaftic.morpher.geckolib3.util.MovementQuery;
import com.micaftic.morpher.util.log.ILogger;
import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public abstract class AnimatableEntity<TEntity extends Entity> {

    private final EntityFrameStateTracker<TEntity> positionTracker;

    public final TEntity entity;

    public final FakePlayerEntity fakePlayerEntity;

    private AnimatedGeoModel currentModel;

    private Object2ReferenceMap<String, List<IValue>> animationMap;

    public boolean wasAnimationActiveLastTick;

    public boolean hasUpdatedThisTick;

    public boolean isTickTriggered;

    public boolean wasEvaluatedLastFrame;

    public float seekTime;

    private int lastAnimationEvaluationFrameId = -1;

    private float lastAnimationEvaluationSeekTime = Float.NaN;

    private boolean lastAnimationEvaluationActive;

    @Nullable
    private AnimatedGeoModel lastAnimationEvaluationModel;

    private int lastAnimationEvaluationBoneCount;

    private int lastAnimationEvaluationControllerCount;

    private boolean lastAnimationEvaluationPreviewMode;

    private boolean lastAnimationEvaluationExtraPlayerMode;

    private float lastAnimationEvaluationHeadPitch = Float.NaN;

    private float lastAnimationEvaluationNetHeadYaw = Float.NaN;

    private final AnimationData manager = new AnimationData();

    public float lastTick = -1.0f;

    public boolean isFirstFrameAfterReset = true;

    public boolean needsReset = false;

    public boolean modelInitialized = false;

    private static final float WALK_SPEED_THRESHOLD = 0.15f;
    private float smoothedRemoteSpeed = 0.0f;

    public Map<String, AnimationState> animationStates = Maps.newHashMap();

    private final AnimationProcessor<TEntity> animationProcessor = new AnimationProcessor<>(this);

    private final RateLimiter rateLimiter = new RateLimiter();

    public final PhysicsManager physicsManager = new PhysicsManager();

    public interface AnimationControllerVisitor extends Consumer<Consumer<IAnimationController<?>>> {
    }

    public abstract Identifier getTextureLocation();

    public abstract boolean isModelReady();

    public abstract float getHeightScale();

    public abstract float getWidthScale();

    @Nullable
    public abstract Animation getAnimation(String str);

    public abstract void registerAnimationControllers();

    public AnimatableEntity(TEntity tentity) {
        this.entity = tentity;
        this.positionTracker = createPositionTracker(tentity);
        this.rateLimiter.setRefreshRate(getRefreshRate());

        if(tentity instanceof LocalPlayer localPlayer) {
            this.fakePlayerEntity = new FakePlayerEntity(localPlayer);
        } else {
            this.fakePlayerEntity = null;
        }
    }

    public void reset() {
        this.currentModel = null;
        this.animationMap = null;
        this.animationProcessor.reset();
        this.physicsManager.clear();
        this.rateLimiter.reset();
        this.manager.clear();
        this.positionTracker.reset();
        this.lastTick = -1.0f;
        this.wasAnimationActiveLastTick = false;
        this.hasUpdatedThisTick = false;
        this.isTickTriggered = false;
        this.isFirstFrameAfterReset = true;
        this.needsReset = false;
        this.wasEvaluatedLastFrame = false;
        this.seekTime = 0.0f;
        this.lastAnimationEvaluationFrameId = -1;
        this.lastAnimationEvaluationSeekTime = Float.NaN;
        this.lastAnimationEvaluationActive = false;
        this.lastAnimationEvaluationModel = null;
        this.lastAnimationEvaluationBoneCount = 0;
        this.lastAnimationEvaluationControllerCount = 0;
        this.lastAnimationEvaluationPreviewMode = false;
        this.lastAnimationEvaluationExtraPlayerMode = false;
        this.lastAnimationEvaluationHeadPitch = Float.NaN;
        this.lastAnimationEvaluationNetHeadYaw = Float.NaN;
        this.animationStates.clear();
        this.smoothedRemoteSpeed = 0.0f;
    }

    public EntityFrameStateTracker<TEntity> createPositionTracker(TEntity tentity) {
        return new EntityFrameStateTracker<>(tentity);
    }

    public EntityFrameStateTracker<TEntity> getPositionTracker() {
        return this.positionTracker;
    }

    public float getSeekTime() {
        return this.seekTime;
    }

    public <T extends AnimatableEntity<TEntity>> void addAnimationController(IAnimationController controller) {
        this.manager.addAnimationController(controller);
    }

    public AnimationData getAnimationData() {
        return this.manager;
    }

    @Nullable
    public IValue resolveExpression(String str) {
        return null;
    }

    public Optional<IAudioStreamFactory> getAudioStreamFactory(String str) {
        return Optional.empty();
    }

    @Nullable
    public final List<IValue> getAnimationExpressions(String str) {
        return this.animationMap.get(str);
    }

    public PhysicsManager getPhysicsManager() {
        return this.physicsManager;
    }

    @Nullable
    public AnimationController getAnimationEntries(String str) {
        return null;
    }

    public int getTextureIndex() {
        return 0;
    }

    public float getScale() {
        return 0.15f;
    }

    public void setupAnim(float seekTime, boolean z) {
    }

    public void afterSetupAnim(float seekTime, boolean z) {
    }

    private boolean isHudRendering = false;

    public void flagHudRendering(boolean flag) {
        this.isHudRendering = flag;
    }

    public final TEntity getEntity() {
        return isHudRendering && fakePlayerEntity != null ? (TEntity) fakePlayerEntity : this.entity;
    }

    public boolean hasCustomTexture() {
        return false;
    }

    @Nullable
    public IBone getBone(int i) {
        return this.animationProcessor.getBone(i);
    }

    public boolean shouldRenderOverlay() {
        return true;
    }

    public int getRefreshRate() {
        if (!GeneralConfig.safeGet(GeneralConfig.ANIMATION_DISTANCE_LOD, false)) {
            return ClientTickEvent.getRefreshRate();
        }
        TEntity tentity = (TEntity) Minecraft.getInstance().player;
        if (tentity != null && tentity != this.entity) {
            Vec3 vec3Position = tentity.position();
            if (vec3Position.x != 0.0d || vec3Position.y != 0.0d || vec3Position.z != 0.0d) {
                if (!this.isFirstFrameAfterReset) {
                    float fDistanceTo = tentity.distanceTo(this.entity);
                    if (fDistanceTo > 64.0f) {
                        return 15;
                    }
                    if (fDistanceTo > 32.0f) {
                        return 30;
                    }
                }
                return ClientTickEvent.getRefreshRate();
            }
        }
        return ClientTickEvent.getRefreshRate();
    }

    @Nullable
    public final AnimationEvent<?> processAnimation(float partialTick) {
        return processAnimationImpl(partialTick, ModelPreviewRenderer.isFirstPersonOnRenderThread());
    }

    @Nullable
    public AnimationEvent<?> processAnimationImpl(float partialTick, boolean z) {
        return processAnimationImpl(partialTick, -1, z);
    }

    @Nullable
    public AnimationEvent<?> processAnimationImpl(float partialTick, int capturedTickCount, boolean z) {
        if (this.currentModel == null) {
            return null;
        }
        partialTick = sanitizePartialTick(partialTick);
        Entity entity = this.entity;
        LivingEntity livingEntity = entity instanceof LivingEntity ? (LivingEntity) entity : null;
        PlayerCapability playerCapability = this instanceof PlayerCapability cap ? cap : null;
        int tickCount = this instanceof IPreviewAnimatable
                ? ClientTickEvent.getTickCount()
                : (capturedTickCount >= 0 ? capturedTickCount : entity.tickCount);
        float frameTime = partialTick;
        boolean shouldSit = entity.isPassenger() && entity.getVehicle() != null && EntityDataBridge.shouldRiderSit(entity.getVehicle());
        float limbSwingAmount = 0.0f;
        float limbSwing = 0.0f;
        if (!shouldSit && entity.isAlive() && livingEntity != null) {
            boolean renderStateMovementSuppressed = false;
            if (playerCapability != null && playerCapability.hasRenderState()) {
                limbSwingAmount = playerCapability.getRenderStateWalkAnimationSpeed();
                limbSwing = playerCapability.getRenderStateWalkAnimationPos();
            } else {
                limbSwingAmount = livingEntity.walkAnimation.speed(partialTick);
                limbSwing = livingEntity.walkAnimation.position(partialTick);
            }
            if (playerCapability != null && !playerCapability.isLocalPlayerModel()) {
                // Remote players: drive the limb cycle from the walkAnimation state (the same
                // source as the local player, partialTick-interpolated on the render thread) so
                // the walk cadence matches the actual movement. Fall back to the position-tracker
                // based physical speed only when the captured state is degenerate while the entity
                // is clearly moving (e.g. the entity was culled and walkAnimation went stale).
                float physicalSpeed = MovementQuery.getPhysicalGroundSpeed(entity, this.positionTracker);
                if (physicalSpeed > WALK_SPEED_THRESHOLD && Math.abs(limbSwingAmount) <= WALK_SPEED_THRESHOLD) {
                    this.smoothedRemoteSpeed += 0.3f * (physicalSpeed - this.smoothedRemoteSpeed);
                    limbSwingAmount = this.smoothedRemoteSpeed;
                    limbSwing = this.seekTime * 0.6662f;
                } else {
                    this.smoothedRemoteSpeed = 0.0f;
                }
                renderStateMovementSuppressed = true;
            }
            if (!renderStateMovementSuppressed && limbSwingAmount <= 1.0E-4f) {
                float movementSpeed = Mth.clamp(MovementQuery.getGroundSpeed(entity, this.positionTracker, null), 0.0f, 1.0f);
                if (movementSpeed > 1.0E-4f) {
                    limbSwingAmount = movementSpeed;
                    limbSwing = this.seekTime * 0.6662f;
                }
            }
            if (livingEntity.isBaby()) {
                limbSwing *= 3.0f;
            }
        }
        EntityModelData modelData = new EntityModelData();
        modelData.isSitting = shouldSit;

        float lerpBodyRot = 0.0f;
        float lerpHeadRot = 0.0f;
        float netHeadYaw = 0.0f;
        if (playerCapability != null && playerCapability.hasRenderState()) {
            modelData.isChild = livingEntity != null && livingEntity.isBaby();
            lerpBodyRot = playerCapability.getRenderStateBodyRot();
            netHeadYaw = playerCapability.getRenderStateNetHeadYaw();
            lerpHeadRot = RiderRotationMath.absoluteHeadYaw(lerpBodyRot, netHeadYaw);
        } else if (livingEntity != null) {
            modelData.isChild = livingEntity.isBaby();
            lerpBodyRot = Mth.rotLerp(partialTick, livingEntity.yBodyRotO, livingEntity.yBodyRot);
            lerpHeadRot = Mth.rotLerp(partialTick, livingEntity.yHeadRotO, livingEntity.yHeadRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        if (shouldSit && (entity.getVehicle() instanceof LivingEntity vehicle)) {
            float vehicleBodyRot = Mth.rotLerp(partialTick, vehicle.yBodyRotO, vehicle.yBodyRot);
            RiderRotationMath.LivingVehicleRotation ridingRotation =
                    RiderRotationMath.constrainToLivingVehicle(lerpHeadRot, vehicleBodyRot);
            lerpBodyRot = ridingRotation.bodyYaw();
            netHeadYaw = ridingRotation.relativeHeadYaw();
        }
        modelData.rawHeadPitch = playerCapability != null && playerCapability.hasRenderState()
                ? playerCapability.getRenderStateHeadPitch()
                : Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        modelData.headPitch = -modelData.rawHeadPitch;
        modelData.rawNetHeadYaw = netHeadYaw;
        modelData.netHeadYaw = -Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85.0f, 85.0f);
        modelData.lerpBodyRot = lerpBodyRot;
        modelData.lerpedAge = tickCount + partialTick;
        AnimationEvent<AnimatableEntity<TEntity>> event = new AnimationEvent<>(this, limbSwing, limbSwingAmount, tickCount, partialTick, frameTime, Math.abs(limbSwingAmount) > 1.0E-4f, z, modelData);
        AnimationContext<?> context = new AnimationContext<>(entity, this, event, modelData);
        context.setLogger(getLogger());
        setCustomAnimations(context, event);
        return event;
    }

    public void setCustomAnimations(AnimationContext<?> ctx, @NotNull AnimationEvent<AnimatableEntity<TEntity>> event) {
        float currentTick = event.currentTick;
        boolean z = !shouldSkipAnimation(event);
        if (currentTick > this.lastTick) {
            this.hasUpdatedThisTick = false;
            this.isTickTriggered = false;
            this.lastTick = currentTick;
            this.rateLimiter.setRefreshRate(getRefreshRate());
            this.isFirstFrameAfterReset = this.needsReset;
            this.needsReset = false;
        } else {
            currentTick = this.lastTick;
        }
        if (this.manager.startTick == -1.0f) {
            this.manager.startTick = currentTick;
        } else {
            float f2 = currentTick - this.manager.startTick;
            float f3 = f2 - this.manager.limbSwing;
            if (f3 > 0.0f) {
                this.manager.limbSwing = f2;
                this.seekTime += f3;
            }
        }
        event.currentTick = this.seekTime;
        this.positionTracker.updateState(event.getTickCount(), this.seekTime, event.getFrameTime());
        if (!this.animationProcessor.isDisabled()) {
            this.isTickTriggered |= this.rateLimiter.request(this.seekTime / 20.0f);
            boolean z2 = (this.isTickTriggered && !this.hasUpdatedThisTick) || this.wasAnimationActiveLastTick || z;
            boolean z3 = (!z || (this.seekTime == 0.0f && !this.hasUpdatedThisTick)) && this.isTickTriggered && !this.hasUpdatedThisTick;
            resetHeadTracking(this.wasEvaluatedLastFrame);
            AnimationFrameProfiler.Scope profilerScope = null;
            if (z2) {
                if (z3) {
                    this.hasUpdatedThisTick = true;
                }
                int renderFrameId = AnimationFrameProfiler.getRenderFrameId();
                int boneCount = getEvaluationContext().getBoneCount();
                int controllerCount = this.manager.getAnimationControllers().size();
                boolean previewMode = ModelPreviewRenderer.isPreview();
                boolean extraPlayerMode = com.micaftic.morpher.client.render.RenderContext.isGuiPreview();
                boolean canReuseEvaluation = (!previewMode || extraPlayerMode)
                        && this.lastAnimationEvaluationFrameId == renderFrameId
                        && Float.compare(this.lastAnimationEvaluationSeekTime, this.seekTime) == 0
                        && this.lastAnimationEvaluationActive == z
                        && this.lastAnimationEvaluationModel == this.currentModel
                        && this.lastAnimationEvaluationBoneCount == boneCount
                        && this.lastAnimationEvaluationControllerCount == controllerCount
                        && this.lastAnimationEvaluationPreviewMode == previewMode
                        && this.lastAnimationEvaluationExtraPlayerMode == extraPlayerMode
                        && Float.compare(this.lastAnimationEvaluationHeadPitch, event.getModelData().headPitch) == 0
                        && Float.compare(this.lastAnimationEvaluationNetHeadYaw, event.getModelData().netHeadYaw) == 0;
                if (canReuseEvaluation) {
                    AnimationFrameProfiler.logReusedEvaluation(this, event, this.seekTime);
                } else {
                    if (this.lastAnimationEvaluationFrameId == renderFrameId) {
                        AnimationFrameProfiler.logReuseMiss(this, event, getReuseMissReason(z, boneCount, controllerCount), this.seekTime, this.lastAnimationEvaluationSeekTime, z, this.lastAnimationEvaluationActive, boneCount, this.lastAnimationEvaluationBoneCount, controllerCount, this.lastAnimationEvaluationControllerCount);
                    }
                    profilerScope = AnimationFrameProfiler.beginEvaluation(this, event, currentTick, this.seekTime, z, z3, boneCount, controllerCount);
                    try {
                        getPhysicsManager().update(this.seekTime);
                        setupAnim(this.seekTime, z3);
                        getEvaluationContext().tickAnimation(event, ctx, z, shouldRenderOverlay());
                        afterSetupAnim(this.seekTime, z3);
                        this.wasAnimationActiveLastTick = z;
                        this.lastAnimationEvaluationFrameId = renderFrameId;
                        this.lastAnimationEvaluationSeekTime = this.seekTime;
                        this.lastAnimationEvaluationActive = z;
                        this.lastAnimationEvaluationModel = this.currentModel;
                        this.lastAnimationEvaluationBoneCount = boneCount;
                        this.lastAnimationEvaluationControllerCount = controllerCount;
                        this.lastAnimationEvaluationPreviewMode = previewMode;
                        this.lastAnimationEvaluationExtraPlayerMode = extraPlayerMode;
                        this.lastAnimationEvaluationHeadPitch = event.getModelData().headPitch;
                        this.lastAnimationEvaluationNetHeadYaw = event.getModelData().netHeadYaw;
                    } finally {
                        AnimationFrameProfiler.endEvaluation(profilerScope);
                    }
                }
            }
            applyHeadTracking(event, z2);
            this.wasEvaluatedLastFrame = z2;
        }
    }

    private static float sanitizePartialTick(float partialTick) {
        if (!Float.isFinite(partialTick)) {
            return 0.0f;
        }
        return Mth.clamp(partialTick, 0.0f, 1.0f);
    }

    private String getReuseMissReason(boolean animationActive, int boneCount, int controllerCount) {
        if (this.lastAnimationEvaluationModel != this.currentModel) {
            return "model_changed";
        }
        if (this.lastAnimationEvaluationBoneCount != boneCount || this.lastAnimationEvaluationControllerCount != controllerCount) {
            return "shape_changed";
        }
        if (Float.compare(this.lastAnimationEvaluationSeekTime, this.seekTime) != 0) {
            return "seekTime_changed";
        }
        if (this.lastAnimationEvaluationActive != animationActive) {
            return "active_changed";
        }
        return "unknown";
    }

    public void applyHeadTracking(AnimationEvent<? extends AnimatableEntity<TEntity>> event, boolean z) {
    }

    public void resetHeadTracking(boolean z) {
    }

    public AnimationProcessor<TEntity> getEvaluationContext() {
        return this.animationProcessor;
    }

    public void initAnimationControllers(@NotNull GeoModel model, Object2ReferenceMap<String, List<IValue>> object2ReferenceMap) {
        reset();
        this.currentModel = new AnimatedGeoModel(model);
        this.animationMap = object2ReferenceMap;
        registerAnimationControllers();
        this.animationProcessor.initBones(this.currentModel, object2ReferenceMap);
        setCurrentModel(this.currentModel);
    }

    public void clearAnimationControllers() {
        if (this.currentModel != null) {
            GeoModel model = this.currentModel.getGeoModel();
            Object2ReferenceMap<String, List<IValue>> object2ReferenceMap = this.animationMap;
            reset();
            initAnimationControllers(model, object2ReferenceMap);
        }
    }

    @Nullable
    public final AnimatedGeoModel getCurrentModel() {
        return this.currentModel;
    }

    public void setCurrentModel(AnimatedGeoModel model) {
    }

    public boolean shouldSkipAnimation(AnimationEvent<?> event) {
        return true;
    }

    public void resetAnimationState() {
        this.needsReset = true;
    }

    public void executeExpression(IValue value, boolean isClientPlayer, boolean executeBeforeAnimation, @Nullable Consumer<String> consumer) {
        this.animationProcessor.execute(value, isClientPlayer, executeBeforeAnimation, consumer);
    }

    public IForeignVariableStorage getPropertyGetter() {
        return this.animationProcessor.getPublicVariableStorage();
    }

    public void markModelInitialized() {
        this.modelInitialized = true;
    }

    public boolean isModelInitialized() {
        return this.modelInitialized;
    }

    @Nullable
    public ILogger getLogger() {
        return null;
    }

    public boolean isDebugMode() {
        return Minecraft.getInstance().level == this.entity.level() && !this.entity.isRemoved();
    }

    public void setAnimationState(String name, AnimationState state) {
        this.animationStates.put(name, state);
    }

    public AnimationState getAnimationState(String name) {
        return this.animationStates.getOrDefault(name, AnimationState.IDLE);
    }

    public static class FakePlayerEntity extends AbstractClientPlayer {

        private final LocalPlayer targetPlayer;

        public FakePlayerEntity(LocalPlayer target) {
            this.targetPlayer = target;
            super((ClientLevel) target.level(), target.getGameProfile());
        }

        public void clonePositionAndRotation() {
            clonePositionAndRotation(this.targetPlayer);
        }

        public void clonePositionAndRotation(AbstractClientPlayer srcPlayer) {

            yRotO = srcPlayer.yRotO;
            xRotO = srcPlayer.xRotO;
            yBodyRotO = srcPlayer.yBodyRotO;
            yHeadRotO = srcPlayer.yHeadRotO;

            setPos(srcPlayer.getX(), srcPlayer.getY(), srcPlayer.getZ());
            setYRot(srcPlayer.getYRot());
            setYHeadRot(srcPlayer.getYHeadRot());
            setYBodyRot(srcPlayer.getYHeadRot());
            setXRot(srcPlayer.getXRot());

        }

        public @NonNull ItemStack getItemBySlot(EquipmentSlot slot) {
            return this.targetPlayer.getItemBySlot(slot);
        }

        @Override
        public PlayerSkin getSkin() {
            return this.targetPlayer.getSkin();
        }

        @Override
        public Parrot.@org.jspecify.annotations.Nullable Variant getParrotVariantOnShoulder(boolean left) {
            return this.targetPlayer.getParrotVariantOnShoulder(left);
        }

        @Override
        public float getFieldOfViewModifier(boolean firstPerson, float effectScale) {
            return this.targetPlayer.getFieldOfViewModifier(firstPerson, effectScale);
        }

        @Override
        public int getDimensionChangingDelay() {
            return this.targetPlayer.getDimensionChangingDelay();
        }

        @Override
        public SoundSource getSoundSource() {
            return this.targetPlayer.getSoundSource();
        }

        @Override
        public int getScore() {
            return this.targetPlayer.getScore();
        }

        @Override
        public ItemStack getWeaponItem() {
            return this.targetPlayer.getWeaponItem();
        }

        @Override
        public float getDestroySpeed(BlockState state) {
            return this.targetPlayer.getDestroySpeed(state);
        }

        @Override
        public float getVoicePitch() {
            return this.targetPlayer.getVoicePitch();
        }

        @Override
        public GameProfile getGameProfile() {
            return this.targetPlayer.getGameProfile();
        }

        @Override
        public Inventory getInventory() {
            return this.targetPlayer.getInventory();
        }

        @Override
        public Abilities getAbilities() {
            return this.targetPlayer.getAbilities();
        }

        @Override
        public int getSleepTimer() {
            return this.targetPlayer.getSleepTimer();
        }

        @Override
        public float getSpeed() {
            return this.targetPlayer.getSpeed();
        }

        @Override
        public Fallsounds getFallSounds() {
            return this.targetPlayer.getFallSounds();
        }

        @Override
        public int getEnchantmentSeed() {
            return this.targetPlayer.getEnchantmentSeed();
        }

        @Override
        public int getXpNeededForNextLevel() {
            return this.targetPlayer.getXpNeededForNextLevel();
        }

        @Override
        public Optional<WardenSpawnTracker> getWardenSpawnTracker() {
            return this.targetPlayer.getWardenSpawnTracker();
        }

        @Override
        public FoodData getFoodData() {
            return this.targetPlayer.getFoodData();
        }

        @Override
        public Component getName() {
            return this.targetPlayer.getName();
        }

        @Override
        public String getPlainTextName() {
            return this.targetPlayer.getPlainTextName();
        }

        @Override
        public PlayerEnderChestContainer getEnderChestInventory() {
            return this.targetPlayer.getEnderChestInventory();
        }

        @Override
        public Component getDisplayName() {
            return this.targetPlayer.getDisplayName();
        }

        @Override
        public String getScoreboardName() {
            return this.targetPlayer.getScoreboardName();
        }

        @Override
        public float getAbsorptionAmount() {
            return this.targetPlayer.getAbsorptionAmount();
        }

        @Override
        public @org.jspecify.annotations.Nullable SlotAccess getSlot(int slot) {
            return this.targetPlayer.getSlot(slot);
        }

        @Override
        public Optional<Parrot.Variant> getShoulderParrotLeft() {
            return this.targetPlayer.getShoulderParrotLeft();
        }

        @Override
        public Optional<Parrot.Variant> getShoulderParrotRight() {
            return this.targetPlayer.getShoulderParrotRight();
        }

        @Override
        public float getCurrentItemAttackStrengthDelay() {
            return this.targetPlayer.getCurrentItemAttackStrengthDelay();
        }

        @Override
        public float getAttackStrengthScale(float a) {
            return this.targetPlayer.getAttackStrengthScale(a);
        }

        @Override
        public float getItemSwapScale(float a) {
            return this.targetPlayer.getItemSwapScale(a);
        }

        @Override
        public ItemCooldowns getCooldowns() {
            return this.targetPlayer.getCooldowns();
        }

        @Override
        public float getLuck() {
            return this.targetPlayer.getLuck();
        }

        @Override
        public ImmutableList<Pose> getDismountPoses() {
            return this.targetPlayer.getDismountPoses();
        }

        @Override
        public ItemStack getProjectile(ItemStack heldWeapon) {
            return this.targetPlayer.getProjectile(heldWeapon);
        }

        @Override
        public Vec3 getRopeHoldPosition(float partialTickTime) {
            return this.targetPlayer.getRopeHoldPosition(partialTickTime);
        }

        @Override
        public Optional<GlobalPos> getLastDeathLocation() {
            return this.targetPlayer.getLastDeathLocation();
        }

        @Override
        public float getHurtDir() {
            return this.targetPlayer.getHurtDir();
        }

        @Override
        public double getContainerInteractionRange() {
            return this.targetPlayer.getContainerInteractionRange();
        }

        @Override
        public ResolvableProfile getProfile() {
            return this.targetPlayer.getProfile();
        }

        @Override
        public boolean isSecondaryUseActive() {
            return this.targetPlayer.isSecondaryUseActive();
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return this.targetPlayer.isInvulnerableTo(level, source);
        }

        @Override
        public boolean isTextFilteringEnabled() {
            return this.targetPlayer.isTextFilteringEnabled();
        }

        @Override
        public boolean isAffectedByFluids() {
            return this.targetPlayer.isAffectedByFluids();
        }

        @Override
        public boolean isClientAuthoritative() {
            return this.targetPlayer.isClientAuthoritative();
        }

        @Override
        public boolean isLocalPlayer() {
            return this.targetPlayer.isLocalPlayer();
        }

        @Override
        public boolean isEffectiveAi() {
            return this.targetPlayer.isEffectiveAi();
        }

        @Override
        public boolean isSleepingLongEnough() {
            return this.targetPlayer.isSleepingLongEnough();
        }

        @Override
        public boolean isHurt() {
            return this.targetPlayer.isHurt();
        }

        @Override
        public boolean isSpectator() {
            return this.targetPlayer.isSpectator();
        }

        @Override
        public boolean isPickable() {
            return this.targetPlayer.isPickable();
        }

        @Override
        public boolean isSwimming() {
            return this.targetPlayer.isSwimming();
        }

        @Override
        public boolean isCreative() {
            return this.targetPlayer.isCreative();
        }

        @Override
        public boolean isPushedByFluid() {
            return this.targetPlayer.isPushedByFluid();
        }

        @Override
        public boolean isReducedDebugInfo() {
            return this.targetPlayer.isReducedDebugInfo();
        }

        @Override
        public boolean isAlwaysTicking() {
            return this.targetPlayer.isAlwaysTicking();
        }

        @Override
        public boolean isScoping() {
            return this.targetPlayer.isScoping();
        }

        @Override
        public boolean isMobilityRestricted() {
            return this.targetPlayer.isMobilityRestricted();
        }

        @Override
        public boolean isWithinEntityInteractionRange(Entity entity, double buffer) {
            return this.targetPlayer.isWithinEntityInteractionRange(entity, buffer);
        }

        @Override
        public boolean isWithinEntityInteractionRange(AABB aabb, double buffer) {
            return this.targetPlayer.isWithinEntityInteractionRange(aabb, buffer);
        }

        @Override
        public boolean isWithinAttackRange(ItemStack weaponItem, AABB aabb, double buffer) {
            return this.targetPlayer.isWithinAttackRange(weaponItem, aabb, buffer);
        }

        @Override
        public boolean isWithinBlockInteractionRange(BlockPos pos, double buffer) {
            return this.targetPlayer.isWithinBlockInteractionRange(pos, buffer);
        }

        @Override
        public boolean isBaby() {
            return this.targetPlayer.isBaby();
        }

        @Override
        public boolean isInvertedHealAndHarm() {
            return this.targetPlayer.isInvertedHealAndHarm();
        }

        @Override
        public boolean isDeadOrDying() {
            return this.targetPlayer.isDeadOrDying();
        }

        @Override
        public boolean isAlive() {
            return this.targetPlayer.isAlive();
        }

        @Override
        public boolean isLookingAtMe(LivingEntity target, double coneSize, boolean adjustForDistance, boolean seeThroughTransparentBlocks, double... gazeHeights) {
            return this.targetPlayer.isLookingAtMe(target, coneSize, adjustForDistance, seeThroughTransparentBlocks, gazeHeights);
        }

        @Override
        public boolean isIgnoringFallDamageFromCurrentImpulse() {
            return this.targetPlayer.isIgnoringFallDamageFromCurrentImpulse();
        }

        @Override
        public boolean isInPostImpulseGraceTime() {
            return this.targetPlayer.isInPostImpulseGraceTime();
        }

        @Override
        public boolean isHolding(Item item) {
            return this.targetPlayer.isHolding(item);
        }

        @Override
        public boolean isHolding(Predicate<ItemStack> itemPredicate) {
            return this.targetPlayer.isHolding(itemPredicate);
        }

        @Override
        public boolean isSensitiveToWater() {
            return this.targetPlayer.isSensitiveToWater();
        }

        @Override
        public boolean isJumping() {
            return this.targetPlayer.isJumping();
        }

        @Override
        public boolean isAutoSpinAttack() {
            return this.targetPlayer.isAutoSpinAttack();
        }

        @Override
        public boolean isPushable() {
            return this.targetPlayer.isPushable();
        }

        @Override
        public boolean isUsingItem() {
            return this.targetPlayer.isUsingItem();
        }

        @Override
        public boolean isBlocking() {
            return this.targetPlayer.isBlocking();
        }

        @Override
        public boolean isSuppressingSlidingDownLadder() {
            return this.targetPlayer.isSuppressingSlidingDownLadder();
        }

        @Override
        public boolean isFallFlying() {
            return this.targetPlayer.isFallFlying();
        }

        @Override
        public boolean isVisuallySwimming() {
            return this.targetPlayer.isVisuallySwimming();
        }

        @Override
        public boolean isAffectedByPotions() {
            return this.targetPlayer.isAffectedByPotions();
        }

        @Override
        public boolean isSleeping() {
            return this.targetPlayer.isSleeping();
        }

        @Override
        public boolean isInWall() {
            return this.targetPlayer.isInWall();
        }

        @Override
        public boolean isCurrentlyGlowing() {
            return this.targetPlayer.isCurrentlyGlowing();
        }

        @Override
        public boolean isTransmittingWaypoint() {
            return this.targetPlayer.isTransmittingWaypoint();
        }

        @Override
        public boolean isModelPartShown(PlayerModelPart part) {
            return this.targetPlayer.isModelPartShown(part);
        }

        @Override
        public boolean isColliding(BlockPos pos, BlockState state) {
            return this.targetPlayer.isColliding(pos, state);
        }

        @Override
        public boolean isOnPortalCooldown() {
            return this.targetPlayer.isOnPortalCooldown();
        }

        @Override
        public boolean isFree(double xa, double ya, double za) {
            return this.targetPlayer.isFree(xa, ya, za);
        }

        @Override
        public boolean isSupportedBy(BlockPos pos) {
            return this.targetPlayer.isSupportedBy(pos);
        }

    }

}
