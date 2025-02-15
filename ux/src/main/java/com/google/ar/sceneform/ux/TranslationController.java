/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.sceneform.ux;

import androidx.annotation.Nullable;
import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.NodeParent;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.collision.RayHit;
import com.google.ar.sceneform.math.MathHelper;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.utilities.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Manipulates the position of a {@link BaseTransformableNode} using a {@link
 * DragGestureRecognizer}. If not selected, the {@link BaseTransformableNode} will become selected
 * when the {@link DragGesture} starts.
 */
public class TranslationController extends BaseTransformationController<DragGesture> {
  @Nullable private HitResult lastArHitResult;
  @Nullable private Vector3 desiredLocalPosition;
  @Nullable private Quaternion desiredLocalRotation;

  private final Vector3 initialForwardInLocal = new Vector3();
  private Vector3 initialOffset = null;

  private EnumSet<Plane.Type> allowedPlaneTypes = EnumSet.allOf(Plane.Type.class);

  private static final float LERP_SPEED = 12.0f;
  private static final float POSITION_LENGTH_THRESHOLD = 0.01f;
  private static final float ROTATION_DOT_THRESHOLD = 0.99f;

  public TranslationController(
      BaseTransformableNode transformableNode, DragGestureRecognizer gestureRecognizer) {
    super(transformableNode, gestureRecognizer);
  }

  /** Sets which types of ArCore Planes this TranslationController is allowed to translate on. */
  public void setAllowedPlaneTypes(EnumSet<Plane.Type> allowedPlaneTypes) {
    this.allowedPlaneTypes = allowedPlaneTypes;
  }

  /**
   * Gets a reference to the EnumSet that determines which types of ArCore Planes this
   * TranslationController is allowed to translate on.
   */
  public EnumSet<Plane.Type> getAllowedPlaneTypes() {
    return allowedPlaneTypes;
  }

  @Override
  public void onUpdated(Node node, FrameTime frameTime) {
    updatePosition(frameTime);
    updateRotation(frameTime);
  }

  @Override
  public boolean isTransforming() {
    // As long as the transformable node is still interpolating towards the final pose, this
    // controller is still transforming.
    return super.isTransforming() || desiredLocalRotation != null || desiredLocalPosition != null;
  }

  @Override
  public boolean canStartTransformation(DragGesture gesture) {
    Node targetNode = gesture.getTargetNode();
    if (targetNode == null) {
      return false;
    }

    BaseTransformableNode transformableNode = getTransformableNode();
    if (targetNode != transformableNode && !targetNode.isDescendantOf(transformableNode)) {
      return false;
    }

    if (!transformableNode.isSelected() && !transformableNode.select()) {
      return false;
    }

    Vector3 initialForwardInWorld = transformableNode.getForward();
    Node parent = transformableNode.getParentNode();
    if (parent != null) {
      initialForwardInLocal.set(parent.worldToLocalDirection(initialForwardInWorld));
    } else {
      initialForwardInLocal.set(initialForwardInWorld);
    }

    return true;
  }

  private boolean isARMode(Scene scene) {
    return scene.getView() instanceof ArSceneView;
  }

  private List<HitResult> arHitTest(DragGesture gesture, Scene scene) {
    if (scene.getView() instanceof ArSceneView) {
      Frame frame = ((ArSceneView) scene.getView()).getArFrame();
      if (frame == null) {
        return new ArrayList<>();
      }

      Camera arCamera = frame.getCamera();
      if (arCamera.getTrackingState() != TrackingState.TRACKING) {
        return new ArrayList<>();
      }
      Vector3 position = gesture.getPosition();
      return frame.hitTest(position.x, position.y);

    }

    return new ArrayList<>();
  }

  private Optional<RayHit> viewerHitTest(DragGesture gesture, Scene scene) {
    com.google.ar.sceneform.Camera camera = scene.getView().getScene().getCamera();
    com.google.ar.sceneform.collision.Plane target = new com.google.ar.sceneform.collision.Plane(getTransformableNode().getWorldPosition(), camera.getForward().negated());
    Vector3 position = gesture.getPosition();
    Ray ray = camera.screenPointToRay(position.x, position.y);
    RayHit hitResult = new RayHit();
    if (target.rayIntersection(ray, hitResult)) {
      return Optional.of(hitResult);
    }
    return Optional.empty();
  }

  private boolean arTransformation(List<HitResult> hitResultList) {
    for (int i = 0; i < hitResultList.size(); i++) {
      HitResult hit = hitResultList.get(i);
      Trackable trackable = hit.getTrackable();
      Pose pose = hit.getHitPose();
      if (trackable instanceof Plane) {
        Plane plane = (Plane) trackable;
        if (plane.isPoseInPolygon(pose) && allowedPlaneTypes.contains(plane.getType())) {
          desiredLocalPosition = new Vector3(pose.tx(), pose.ty(), pose.tz());
          desiredLocalRotation = new Quaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw());
          Node parent = getTransformableNode().getParentNode();
          if (parent != null && desiredLocalPosition != null && desiredLocalRotation != null) {
            desiredLocalPosition = parent.worldToLocalPoint(desiredLocalPosition);
            desiredLocalRotation =
                    Quaternion.multiply(
                            parent.getWorldRotation().inverted(),
                            Preconditions.checkNotNull(desiredLocalRotation));
          }

          desiredLocalRotation =
                  calculateFinalDesiredLocalRotation(Preconditions.checkNotNull(desiredLocalRotation));
          lastArHitResult = hit;
          return true;
        }
      }
    }
    return false;
  }

  private boolean viewTransformation(RayHit hitResult, Vector3 planeNormal) {
    desiredLocalPosition = hitResult.getPoint();
    desiredLocalRotation = Quaternion.lookRotation(Vector3.forward(), planeNormal);
    Node parent = getTransformableNode().getParentNode();
    if (parent != null && desiredLocalPosition != null && desiredLocalRotation != null) {
      Vector3 desiredWorldPosition = parent.worldToLocalPoint(desiredLocalPosition);
      if (initialOffset == null) {
        initialOffset = Vector3.subtract(getTransformableNode().getWorldPosition(), desiredWorldPosition);
      }
      desiredLocalPosition = parent.worldToLocalPoint(Vector3.add(desiredWorldPosition, initialOffset));
      desiredLocalRotation =
              Quaternion.multiply(
                      parent.getWorldRotation().inverted(),
                      Preconditions.checkNotNull(desiredLocalRotation));
    }

    desiredLocalRotation =
            calculateFinalDesiredLocalRotation(Preconditions.checkNotNull(desiredLocalRotation));
    return true;
  }

  @Override
  public void onContinueTransformation(DragGesture gesture) {
    Scene scene = getTransformableNode().getScene();
    if (scene == null) {
      return;
    }

    if (isARMode(scene)) {
      List<HitResult> hitResultList = arHitTest(gesture, scene);
      arTransformation(hitResultList);
    } else {
      com.google.ar.sceneform.Camera camera = scene.getView().getScene().getCamera();
      viewerHitTest(gesture, scene).map(rayHit -> viewTransformation(rayHit, camera.getForward().negated()));
    }

  }

  @Override
  public void onEndTransformation(DragGesture gesture) {
    HitResult hitResult = lastArHitResult;
    if (hitResult == null) {
      desiredLocalPosition = null;
      desiredLocalRotation = null;
      initialOffset = null;
      return;
    }

    if (hitResult.getTrackable().getTrackingState() == TrackingState.TRACKING) {
      AnchorNode anchorNode = getAnchorNodeOrDie();

      Anchor oldAnchor = anchorNode.getAnchor();
      if (oldAnchor != null) {
        oldAnchor.detach();
      }

      Anchor newAnchor = hitResult.createAnchor();

      Vector3 worldPosition = getTransformableNode().getWorldPosition();
      Quaternion worldRotation = getTransformableNode().getWorldRotation();
      Quaternion finalDesiredWorldRotation = worldRotation;

      // Since we change the anchor, we need to update the initialForwardInLocal into the new
      // coordinate space. Local variable for nullness analysis.
      Quaternion desiredLocalRotation = this.desiredLocalRotation;
      if (desiredLocalRotation != null) {
        getTransformableNode().setLocalRotation(desiredLocalRotation);
        finalDesiredWorldRotation = getTransformableNode().getWorldRotation();
      }

      anchorNode.setAnchor(newAnchor);

      // Temporarily set the node to the final world rotation so that we can accurately
      // determine the initialForwardInLocal in the new coordinate space.
      getTransformableNode().setWorldRotation(finalDesiredWorldRotation);
      Vector3 initialForwardInWorld = getTransformableNode().getForward();
      initialForwardInLocal.set(anchorNode.worldToLocalDirection(initialForwardInWorld));

      getTransformableNode().setWorldRotation(worldRotation);
      getTransformableNode().setWorldPosition(worldPosition);
    }

    desiredLocalPosition = Vector3.zero();
    desiredLocalRotation = calculateFinalDesiredLocalRotation(Quaternion.identity());
  }

  private AnchorNode getAnchorNodeOrDie() {
    NodeParent parent = getTransformableNode().getParent();
    if (!(parent instanceof AnchorNode)) {
      throw new IllegalStateException("TransformableNode must have an AnchorNode as a parent.");
    }

    return (AnchorNode) parent;
  }

  private void updatePosition(FrameTime frameTime) {
    // Store in local variable for nullness static analysis.
    Vector3 desiredLocalPosition = this.desiredLocalPosition;
    if (desiredLocalPosition == null) {
      return;
    }

    Vector3 localPosition = getTransformableNode().getLocalPosition();
    float lerpFactor = MathHelper.clamp(frameTime.getDeltaSeconds() * LERP_SPEED, 0, 1);
    localPosition = Vector3.lerp(localPosition, desiredLocalPosition, lerpFactor);

    float lengthDiff = Math.abs(Vector3.subtract(desiredLocalPosition, localPosition).length());
    if (lengthDiff <= POSITION_LENGTH_THRESHOLD) {
      localPosition = desiredLocalPosition;
      this.desiredLocalPosition = null;
    }

    getTransformableNode().setLocalPosition(localPosition);
  }

  private void updateRotation(FrameTime frameTime) {
    // Store in local variable for nullness static analysis.
    Quaternion desiredLocalRotation = this.desiredLocalRotation;
    if (desiredLocalRotation == null) {
      return;
    }

    Quaternion localRotation = getTransformableNode().getLocalRotation();
    float lerpFactor = MathHelper.clamp(frameTime.getDeltaSeconds() * LERP_SPEED, 0, 1);
    localRotation = Quaternion.slerp(localRotation, desiredLocalRotation, lerpFactor);

    float dot = Math.abs(dotQuaternion(localRotation, desiredLocalRotation));
    if (dot >= ROTATION_DOT_THRESHOLD) {
      localRotation = desiredLocalRotation;
      this.desiredLocalRotation = null;
    }

    getTransformableNode().setLocalRotation(localRotation);
  }

  /**
   * When translating, the up direction of the node must match the up direction of the plane from
   * the hit result. However, we also need to make sure that the original forward direction of the
   * node is respected.
   */
  private Quaternion calculateFinalDesiredLocalRotation(Quaternion desiredLocalRotation) {
    // Get a rotation just to the up direction.
    // Otherwise, the node will spin around as you rotate.
    Vector3 rotatedUp = Quaternion.rotateVector(desiredLocalRotation, Vector3.up());
    desiredLocalRotation = Quaternion.rotationBetweenVectors(Vector3.up(), rotatedUp);

    // Adjust the rotation to make sure the node maintains the same forward direction.
    Quaternion forwardInLocal =
        Quaternion.rotationBetweenVectors(Vector3.forward(), initialForwardInLocal);
    desiredLocalRotation = Quaternion.multiply(desiredLocalRotation, forwardInLocal);

    return desiredLocalRotation.normalized();
  }

  private static float dotQuaternion(Quaternion lhs, Quaternion rhs) {
    return lhs.x * rhs.x + lhs.y * rhs.y + lhs.z * rhs.z + lhs.w * rhs.w;
  }
}
