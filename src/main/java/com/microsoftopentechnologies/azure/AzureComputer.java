/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoftopentechnologies.azure;

import static hudson.model.Computer.DELETE;

import hudson.model.AbstractProject;
import hudson.model.Executor;
import hudson.model.Hudson;
import java.io.IOException;
import java.util.logging.Logger;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import java.util.logging.Level;
import javax.servlet.ServletException;
import org.kohsuke.stapler.StaplerRequest;

public class AzureComputer extends AbstractCloudComputer<AzureSlave> {

    private static final Logger LOGGER = Logger.getLogger(AzureComputer.class.getName());

    private boolean provisioned = false;

    private final Object tsafe = new Object();

    /**
     * Parameter in request for deleting the slave.
     */
    private static final String DELETE_MODE_KEY = "deleteMode";

    /**
     * Key for delete mode when the jobs are stopping before the slave will delete
     */
    private static final String DELETE_MODE_STOP_KEY = "0";

    public AzureComputer(final AzureSlave slave) {
        super(slave);
    }

    @Override
    @SuppressWarnings({ "rawtypes" })
    public HttpResponse doDeleteWithParam(final StaplerRequest req) throws IOException, ServletException {
        checkPermission(DELETE);
        String deleteMode = req.getParameter(DELETE_MODE_KEY);
        if (deleteMode != null) {
            if (DELETE_MODE_STOP_KEY.equals(deleteMode)) {
                if (getRunningJobs() != null) {
                    for (AbstractProject job : getRunningJobs()) {
                        Hudson.getInstance().getQueue().cancel(job);
                    }
                }
                for (Executor executor : getExecutors()) {
                    executor.interrupt();
                }
            }
        }

        return doDoDelete();
    }

    @Override
    public HttpResponse doDoDelete() throws IOException {
        checkPermission(DELETE);
        AzureSlave slave = getNode();

        if (slave != null) {
            LOGGER.log(Level.INFO, "AzureComputer: doDoDelete called for slave {0}", slave.getNodeName());
            setTemporarilyOffline(true, OfflineCause.create(Messages._Delete_Slave()));
            slave.setDeleteSlave(true);

            try {
                deleteSlave();
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "AzureComputer: doDoDelete: Exception occurred while deleting slave", e);

                throw new IOException(
                        "Error deleting node, jenkins will try to clean up node automatically after some time. ", e);
            }
        }
        return new HttpRedirect("..");
    }

    public void deleteSlave() throws Exception, InterruptedException {
        LOGGER.log(Level.INFO, "AzureComputer : deleteSlave: Deleting {0} slave", getName());

        AzureSlave slave = getNode();

        if (slave != null) {
            if (slave.getChannel() != null) {
                slave.getChannel().close();
            }

            try {
                slave.deprovision();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "AzureComputer : Exception occurred while deleting  {0} slave", getName());
                LOGGER.log(Level.SEVERE, "Root cause", e);
                throw e;
            }
        }
    }

    public void setProvisioned(boolean provisioned) {
        this.provisioned = provisioned;
    }

    public boolean isProvisioned() {
        return this.provisioned;
    }

    public void waitUntilOnline() throws InterruptedException {
        synchronized (this.tsafe) {
            while (!this.isOnline()) {
                this.wait(1000);
            }
            setProvisioned(true);
        }
    }
}
