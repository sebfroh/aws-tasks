/**
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package datameer.awstasks.aws.ec2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;

import datameer.awstasks.aws.ec2.ssh.SshClient;
import datameer.awstasks.aws.ec2.ssh.SshClientImpl;
import datameer.awstasks.ssh.JschRunner;
import datameer.awstasks.util.Ec2Util;
import datameer.awstasks.util.ExceptionUtil;
import datameer.awstasks.util.Filters;

public class InstanceGroupImpl implements InstanceGroup {

    private static Logger LOG = Logger.getLogger(InstanceGroupImpl.class);

    private final AmazonEC2 _ec2;
    private final boolean _inlcudeMultipleReservations;
    private List<com.amazonaws.services.ec2.model.Instance> _instances;

    public InstanceGroupImpl(AmazonEC2 ec2) {
        this(ec2, false);
    }

    public InstanceGroupImpl(AmazonEC2 ec2, boolean inlcudeMultipleReservations) {
        _ec2 = ec2;
        _inlcudeMultipleReservations = inlcudeMultipleReservations;
    }

    @Override
    public void connectTo(String groupName) {
        checkEc2Association(false);
        LOG.info(String.format("connecting to instances of group '%s'", groupName));
        _instances = Ec2Util.findByGroup(_ec2, groupName, _inlcudeMultipleReservations, InstanceStateName.Pending, InstanceStateName.Running);
        if (_instances == null) {
            throw new IllegalArgumentException("no instances of group '" + groupName + "' running");
        }
        if (!InstanceStateName.Running.name().equalsIgnoreCase(_instances.get(0).getState().getName())) {
            waitUntilServerUp(TimeUnit.MINUTES, 10);
        }
    }

    @Override
    public void connectTo(Reservation reservation) {
        checkEc2Association(false);
        LOG.info(String.format("connecting to reservation '%s'", reservation.getReservationId()));
        _instances = reservation.getInstances();
        updateInstanceDescriptions();
    }

    @Override
    public Reservation startup(RunInstancesRequest launchConfiguration) {
        return startup(launchConfiguration, null, 0);
    }

    @Override
    public Reservation startup(RunInstancesRequest launchConfiguration, TimeUnit timeUnit, long time) {
        checkEc2Association(false);
        LOG.info(String.format("starting %d to %d instances with %s in groups %s...", launchConfiguration.getMinCount(), launchConfiguration.getMaxCount(), launchConfiguration.getImageId(),
                launchConfiguration.getSecurityGroups()));
        Reservation reservation = _ec2.runInstances(launchConfiguration).getReservation();
        _instances = reservation.getInstances();
        List<String> instanceIds = Ec2Util.toIds(_instances);
        LOG.info(String.format("triggered start of %d instances: %s", instanceIds.size(), instanceIds));
        if (timeUnit != null) {
            waitUntilServerUp(timeUnit, time);
            LOG.info(String.format("started %d instances: %s / %s", instanceIds.size(), instanceIds, Ec2Util.toPublicDns(_instances)));
        }
        return Ec2Util.reloadReservation(_ec2, reservation);
    }

    private void checkEc2Association(boolean shouldBeAssociated) {
        if (shouldBeAssociated && !isAssociated()) {
            throw new IllegalStateException("instance group is not yet associated with ec2 instances");
        }
        if (!shouldBeAssociated && isAssociated()) {
            throw new IllegalStateException("instance group already associated with ec2 instances");
        }
    }

    @Override
    public boolean isAssociated() {
        return _instances != null;
    }

    @Override
    public int instanceCount() {
        checkEc2Association(true);
        return _instances.size();
    }

    @Override
    public void shutdown() {
        checkEc2Association(true);
        TerminateInstancesResult result = _ec2.terminateInstances(new TerminateInstancesRequest(Ec2Util.toIds(_instances)));
        _instances = null;
        LOG.info("stopped " + result.getTerminatingInstances().size() + " instances");
    }

    private List<Instance> waitUntilServerUp(TimeUnit timeUnit, long waitTime) {
        long end = System.currentTimeMillis() + timeUnit.toMillis(waitTime);
        List<String> unexpectedStates = new ArrayList<String>();
        do {
            unexpectedStates.clear();
            try {
                long sleepTime = 10000;
                LOG.info(String.format("wait on instances %s to enter 'running' mode. Sleeping %d ms. zzz...", Ec2Util.getSecurityGroups(_instances), sleepTime));
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            updateInstanceDescriptions();
            List<Instance> startingInstances = _instances;
            for (Instance instance : startingInstances) {
                if (!InstanceStateName.Running.name().equalsIgnoreCase(instance.getState().getName())) {
                    unexpectedStates.add(instance.getState().getName());
                }
                if (InstanceStateName.Terminated.name().equalsIgnoreCase(instance.getState().getName())) {
                    throw new IllegalStateException("instance for " + instance.getSecurityGroups() + " terminated:" + instance.getStateTransitionReason());
                }
            }
        } while (!unexpectedStates.isEmpty() && System.currentTimeMillis() < end);
        if (!unexpectedStates.isEmpty()) {
            throw new IllegalStateException("not all instance of group '" + Ec2Util.getSecurityGroups(_instances) + "' are in state 'running', some are in: " + unexpectedStates);
        }
        return _instances;
    }

    @Override
    public List<Instance> getInstances(boolean updateBefore) {
        checkEc2Association(true);
        if (updateBefore) {
            updateInstanceDescriptions();
        }
        return _instances;
    }

    private synchronized void updateInstanceDescriptions() {
        _instances = Ec2Util.reloadInstanceDescriptions(_ec2, _instances);
    }

    @Override
    public SshClient createSshClient(String username, File privateKey) {
        List<String> instanceDns = checkSshPreconditions();
        checkSshConnection(username, instanceDns, privateKey, null);
        return new SshClientImpl(username, privateKey, instanceDns);
    }

    private List<String> checkSshPreconditions() {
        checkEc2Association(true);
        updateInstanceDescriptions();
        checkInstanceMode(_instances, InstanceStateName.Running);
        List<String> instanceDns = Ec2Util.toPublicDns(_instances);
        checkSshPermissions();
        return instanceDns;
    }

    @Override
    public SshClient createSshClient(String username, String password) {
        List<String> instanceDns = checkSshPreconditions();
        checkSshConnection(username, instanceDns, null, password);
        return new SshClientImpl(username, password, instanceDns);
    }

    private void checkSshConnection(String username, List<String> instanceDns, File privateKey, String password) {
        LOG.info("checking ssh connections of " + username + "@" + instanceDns);
        for (String dns : instanceDns) {
            JschRunner runner = new JschRunner(username, dns);
            if (privateKey != null) {
                runner.setKeyfile(new File(privateKey.getAbsolutePath()));
            } else {
                runner.setPassword(password);
            }
            runner.setTrust(true);
            try {
                runner.testConnect(TimeUnit.MINUTES.toMillis(5));
            } catch (IOException e) {
                throw ExceptionUtil.convertToRuntimeException(e);
            }
        }
    }

    private void checkSshPermissions() {
        GroupPermission sshPermission = GroupPermission.createStandardSsh();
        List<IpPermission> ipPermissions = Ec2Util.getPermissions(_ec2, Ec2Util.getSecurityGroups(_instances), Filters.permissionProtocol(sshPermission.getProtocol()));
        if (ipPermissions.isEmpty()) {
            throw new IllegalStateException("no permission for '" + sshPermission + "' set");
        }
        boolean foundMatching = false;
        for (IpPermission ipPermission : ipPermissions) {
            if (sshPermission.matches(ipPermission)) {
                foundMatching = true;
                break;
            }
        }
        if (!foundMatching) {
            throw new IllegalStateException("found permission for protocol '" + sshPermission.getProtocol() + " but with diverse ports (need " + sshPermission + ")");
        }
    }

    private void checkInstanceMode(List<Instance> instances, InstanceStateName desiredMode) {
        for (Instance instance : instances) {
            if (!instance.getState().getName().equals(desiredMode.toString())) {
                throw new IllegalStateException("instance " + instance.getInstanceId() + " is not in mode '" + desiredMode + "' but in mode '" + instance.getState() + "'");
            }
        }
    }

}
