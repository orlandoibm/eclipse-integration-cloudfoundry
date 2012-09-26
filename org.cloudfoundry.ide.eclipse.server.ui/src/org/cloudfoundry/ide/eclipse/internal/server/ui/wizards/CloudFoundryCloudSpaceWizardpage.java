/*******************************************************************************
 * Copyright (c) 2012 VMware, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     VMware, Inc. - initial API and implementation
 *******************************************************************************/
package org.cloudfoundry.ide.eclipse.internal.server.ui.wizards;

import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryServer;
import org.cloudfoundry.ide.eclipse.internal.server.ui.CloudSpacesSelectionPart;
import org.cloudfoundry.ide.eclipse.internal.server.ui.editor.CloudSpaceChangeNotifier;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.widgets.Composite;

public class CloudFoundryCloudSpaceWizardpage extends WizardPage {

	private CloudSpaceChangeNotifier spaceChangeNotifier;

	private final CloudFoundryServer cloudServer;

	protected CloudFoundryCloudSpaceWizardpage(CloudFoundryServer cloudServer,
			CloudSpaceChangeNotifier spaceChangeNotifier) {
		super(cloudServer.getServer().getName() + " Organization and Spaces");
		this.cloudServer = cloudServer;
		this.spaceChangeNotifier = spaceChangeNotifier;
	}

	public void createControl(Composite parent) {
		CloudSpacesSelectionPart spacesPart = new CloudSpacesSelectionPart(spaceChangeNotifier, cloudServer, this);
		Composite composite = spacesPart.createComposite(parent);
		setControl(composite);
	}

	public boolean isPageComplete() {
		return spaceChangeNotifier != null && spaceChangeNotifier.hasSetSpace();
	}

}
