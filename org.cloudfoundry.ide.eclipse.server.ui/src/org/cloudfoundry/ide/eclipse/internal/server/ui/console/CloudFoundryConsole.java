/*******************************************************************************
 * Copyright (c) 2012, 2013 GoPivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     GoPivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.cloudfoundry.ide.eclipse.internal.server.ui.console;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.eclipse.ui.console.MessageConsole;

/**
 * 
 * The Console job for applications running on Cloud Foundry works on the
 * principle of a self-scheduling job,which continues to schedule itself to
 * check for new content in remote files whose content are streamed to the
 * console.
 * <p/>
 * The console job is passed a console content , which contains a list of files,
 * each wrapped around a streaming abstraction, that need to be polled during
 * the active life of the job. The console job itself does not know how to
 * stream the file contents, and it delegates to the file streaming abstraction
 * to actually write to the console. The role of the console job is to decided
 * when to schedule itself, when to stop, and manage the console output streams.
 * 
 * <p/>
 * The job continues to run (i.e. schedule itself) after some interval until it
 * is told to stop by the CF server controller, most likely under these
 * conditions:
 * <p/>
 * 1. The application is stopped
 * <p/>
 * 2. The application is deleted
 * <p/>
 * 3. The application is restarted
 * <p/>
 * 4. The particular file that is streamed to the console generates repeated
 * errors above a threshold value of admisable number of errors. If a particular
 * file generates too many errors, the stream to that file is closed, and the
 * file is removed from the list of files that needs to be polled. That way
 * polling of other files are unaffected if streaming content of a particular
 * file repeatedly fails.
 * <p/>
 * In all these cases, the output stream to the console is closed, and further
 * polling for new content is terminated. An explicit starting of the tailing
 * operation will start the job again, and create new output streams to the
 * console. Closing output streams does NOT cancel or terminate the job itself.
 * The job keeps running in all cases, EXCEPT when an application is deleted.
 * See the Console Manager for further details.
 * 
 * @author Steffen Pingel
 * @author Christian Dupuis
 * @author Nieraj Singh
 */
class CloudFoundryConsole extends Job {

	static final String ATTRIBUTE_SERVER = "org.cloudfoundry.ide.eclipse.server.Server";

	static final String ATTRIBUTE_APP = "org.cloudfoundry.ide.eclipse.server.CloudApp";

	static final String ATTRIBUTE_INSTANCE = "org.cloudfoundry.ide.eclipse.server.CloudInstance";

	static final String CONSOLE_TYPE = "org.cloudfoundry.ide.eclipse.server.appcloud";

	private Map<ICloudFoundryConsoleOutputStream, Integer> activeStreams;

	/** How frequently to check for log changes; defaults to 5 seconds */
	private long sampleInterval = 5000;

	/** Is the tailer currently tailing? */
	private boolean tailing = true;

	private final MessageConsole console;

	public CloudFoundryConsole(CloudApplication app, MessageConsole console) {
		super(getConsoleName(app));

		this.console = console;

		setSystem(true);
	}

	/**
	 * Starts the job and creates new output streams to the console for each
	 * file listed in the console contents.
	 * @param contents to stream to the console
	 */
	public synchronized void startTailing(ConsoleContents contents) {
		// Stop any current tailing that wasn't explicitly stopped already(e.g.
		// if a
		// user clicks "Restart", which does
		// not perform an application stop)
		stopTailing();

		if (contents != null) {
			List<IConsoleContent> consoleContents = contents.getContents();
			if (consoleContents != null && !consoleContents.isEmpty()) {

				activeStreams = new LinkedHashMap<ICloudFoundryConsoleOutputStream, Integer>();
				for (IConsoleContent content : consoleContents) {
					IOConsoleOutputStream stream = console.newOutputStream();
					if (stream != null) {
						ICloudFoundryConsoleOutputStream outStream = content.getOutputStream(stream);
						if (outStream != null) {
							activeStreams.put(outStream, 0);
						}
						else {
							try {
								stream.close();
							}
							catch (IOException ioe) {
								// Ignore;
							}
						}
					}
				}

				if (!activeStreams.isEmpty()) {
					tailing = true;
					schedule();
				}
			}
		}
	}

	/**
	 * Stops any further streaming of file content, and closes any open output
	 * stream. NOTE that this does NOT terminate or cancel the console job. The
	 * job will continue being alive, but not running, until an explicit
	 * "start tailing" request is made, at which point , new output streams to
	 * the console are created, and the job rescheduled.
	 * 
	 */
	public synchronized void stopTailing() {

		tailing = false;
		if (activeStreams != null) {
			for (Entry<ICloudFoundryConsoleOutputStream, Integer> entry : activeStreams.entrySet()) {
				try {
					entry.getKey().close();
				}
				catch (IOException ioe) {
					// Ignore
				}
			}
			activeStreams = null;
		}

	}

	@Override
	protected synchronized IStatus run(IProgressMonitor monitor) {
		if (this.tailing) {

			// Stream content to the console during this iteration of the job,
			// checking for any errors , and closing output streams
			// for any files that generated too many errors. Further job
			// iterations are scheduled only if there are any remaining
			// files that haven't generated any errors during content streaming.
			if (activeStreams != null && !activeStreams.isEmpty()) {

				Map<ICloudFoundryConsoleOutputStream, Integer> copy = new HashMap<ICloudFoundryConsoleOutputStream, Integer>(
						activeStreams);
				for (Entry<ICloudFoundryConsoleOutputStream, Integer> entry : copy.entrySet()) {
					int count = entry.getValue();
					ICloudFoundryConsoleOutputStream stream = entry.getKey();
					boolean ioexception = false;
					try {
						stream.write(monitor);
					}
					catch (CoreException e) {

						Throwable t = e.getCause();
						// If IOException encountered, terminate right away
						// rather than trying again
						if (t instanceof IOException) {
							ioexception = true;
						}
						count++;
					}

					activeStreams.remove(stream);

					if (ioexception || count > 5) {
						try {
							stream.close();
						}
						catch (IOException ioe) {
							// Ignore
						}
					}
					else {
						activeStreams.put(stream, count);
					}
				}
			}

			if (activeStreams != null && !activeStreams.isEmpty()) {
				schedule(sampleInterval);
			}
			else {
				stopTailing();
			}

		}
		return Status.OK_STATUS;
	}

	static String getConsoleName(CloudApplication app) {
		String name = (app.getUris() != null && app.getUris().size() > 0) ? app.getUris().get(0) : app.getName();
		return name;
	}

	public MessageConsole getConsole() {
		return console;
	}

}
