/*
 * Copyright 2024 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.sherlock.newProject.ui;

import com.google.sherlock.newProject.steps.SherlockNewProjectStep;
import com.intellij.ide.util.projectWizard.AbstractNewProjectDialog;

/**
 * The dialog for creating a new Sherlock project.
 * This dialog guides the user through the steps of setting up a new project.
 */
public class SherlockNewProjectDialog extends AbstractNewProjectDialog {
  @Override
  protected SherlockNewProjectStep createRootStep() {
    return new SherlockNewProjectStep();
  }

  @Override
  protected String getHelpId() {
    return "concepts.project";
  }
}