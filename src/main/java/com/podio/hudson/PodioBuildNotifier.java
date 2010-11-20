package com.podio.hudson;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.podio.BaseAPI;
import com.podio.contact.ContactAPI;
import com.podio.contact.ProfileField;
import com.podio.contact.ProfileType;
import com.podio.item.FieldValues;
import com.podio.item.ItemAPI;
import com.podio.item.ItemCreate;
import com.podio.oauth.OAuthClientCredentials;
import com.podio.oauth.OAuthUsernameCredentials;
import com.podio.root.RootAPI;
import com.podio.root.SystemStatus;
import com.podio.space.SpaceAPI;
import com.podio.space.SpaceWithOrganization;
import com.podio.user.UserMini;
import com.sun.jersey.api.client.UniformInterfaceException;

public class PodioBuildNotifier extends Notifier {

	protected static final Logger LOGGER = Logger
			.getLogger(PodioBuildNotifier.class.getName());

	private final String username;
	private final String password;
	private final String clientId;
	private final String clientSecret;
	private final String spaceURL;

	@DataBoundConstructor
	public PodioBuildNotifier(String username, String password,
			String clientId, String clientSecret, String spaceURL) {
		this.username = username;
		this.password = password;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.spaceURL = spaceURL;

		LOGGER.info(username);
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public String getSpaceURL() {
		return spaceURL;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	private BaseAPI getBaseAPI() {
		DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();

		return new BaseAPI(descriptor.hostname, descriptor.hostname,
				descriptor.port, descriptor.ssl, false,
				new OAuthClientCredentials(clientId, clientSecret),
				new OAuthUsernameCredentials(username, password));
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		LOGGER.info("Build complete");
		BaseAPI baseAPI = getBaseAPI();

		String result = StringUtils.capitalize(build.getResult().toString());
		result = result.replace('_', ' ');
		LOGGER.info(result);
		SpaceWithOrganization space = getSpace(baseAPI);
		LOGGER.info(space.getName());
		List<Integer> userIds = getUserIds(baseAPI, space.getId(), build);

		int totalTestCases = build.getTestResultAction().getTotalCount();
		int failedTestCases = build.getTestResultAction().getFailCount();

		postBuild(baseAPI, build.getNumber(), result, userIds, totalTestCases,
				failedTestCases);

		return true;
	}

	private void postBuild(BaseAPI baseAPI, int buildNumber, String result,
			List<Integer> userIds, int totalTestCases, int failedTestCases) {
		List<FieldValues> fields = new ArrayList<FieldValues>();
		fields.add(new FieldValues(74278, "value", Integer
				.toString(buildNumber)));
		fields.add(new FieldValues(74279, "value", result));
		List<Map<String, Object>> subValues = new ArrayList<Map<String, Object>>();
		for (Integer userId : userIds) {
			subValues.add(Collections.<String, Object> singletonMap("value",
					userId));
		}
		fields.add(new FieldValues(74280, subValues));
		fields.add(new FieldValues(74279, "value", totalTestCases));
		fields.add(new FieldValues(74279, "value", failedTestCases));
		ItemCreate create = new ItemCreate(Integer.toString(buildNumber),
				fields, Collections.<Integer> emptyList(),
				Collections.<String> emptyList());

		ItemAPI itemAPI = new ItemAPI(baseAPI);
		int itemId = itemAPI.addItem(13658, create, true).getItemId();

		LOGGER.info(Integer.toString(itemId));
	}

	private SpaceWithOrganization getSpace(BaseAPI baseAPI) {
		return new SpaceAPI(baseAPI).getByURL(spaceURL);
	}

	private List<Integer> getUserIds(BaseAPI baseAPI, int spaceId,
			AbstractBuild<?, ?> build) {
		List<Integer> userIds = new ArrayList<Integer>();

		Set<User> culprits = build.getCulprits();
		ChangeLogSet<? extends Entry> changeSet = build.getChangeSet();
		if (culprits.size() > 0) {
			for (User culprit : culprits) {
				LOGGER.info("Looking for user " + culprit);
				Integer userId = getUserId(baseAPI, spaceId, culprit);
				LOGGER.info("Found " + userId);
				if (userId != null) {
					userIds.add(userId);
				}
			}
		} else if (changeSet != null) {
			for (Entry entry : changeSet) {
				LOGGER.info("Looking for user " + entry.getAuthor());
				Integer userId = getUserId(baseAPI, spaceId, entry.getAuthor());
				LOGGER.info("Found " + userId);
				if (userId != null) {
					userIds.add(userId);
				}
			}
		}

		LOGGER.info(userIds.toString());

		return userIds;
	}

	private Integer getUserId(BaseAPI baseAPI, int spaceId, User user) {
		List<UserMini> contacts = new ContactAPI(baseAPI).getSpaceContacts(
				spaceId, ProfileField.MAIL, user.getId(), 1, null,
				ProfileType.MINI, null);
		if (contacts.isEmpty()) {
			return null;
		}

		return contacts.get(0).getId();
	}

	@Extension
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		private String hostname = "localhost";

		private int port = 9090;

		private boolean ssl = false;

		public DescriptorImpl() {
			super(PodioBuildNotifier.class);
			load();
		}

		@Override
		public String getDisplayName() {
			return "Podio Build Poster";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
			req.bindParameters(this);
			save();
			return super.configure(req, formData);
		}

		public FormValidation doValidateAuth(
				@QueryParameter("username") final String username,
				@QueryParameter("password") final String password,
				@QueryParameter("clientId") final String clientId,
				@QueryParameter("clientSecret") final String clientSecret,
				@QueryParameter("spaceURL") final String spaceURL)
				throws IOException, ServletException {
			BaseAPI baseAPI = new BaseAPI(hostname, hostname, port, ssl, false,
					new OAuthClientCredentials(clientId, clientSecret),
					new OAuthUsernameCredentials(username, password));

			try {
				SpaceWithOrganization space = new SpaceAPI(baseAPI)
						.getByURL(spaceURL);
				return FormValidation.ok("Connection ok, using space "
						+ space.getName() + " in organization "
						+ space.getOrganization().getName());
			} catch (UniformInterfaceException e) {
				if (e.getResponse().getStatus() == 404) {
					return FormValidation.error("No space found with the URL "
							+ spaceURL);
				} else {
					return FormValidation.error("Invalid username or password");
				}
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Invalid username or password");
			}
		}

		public FormValidation doValidateAPI(
				@QueryParameter("hostname") final String hostname,
				@QueryParameter("port") final String port,
				@QueryParameter("ssl") final boolean ssl) throws IOException,
				ServletException {
			int portInt;
			try {
				portInt = Integer.parseInt(port);
			} catch (NumberFormatException e) {
				return FormValidation.error("Port must be an integer");
			}

			BaseAPI baseAPI = new BaseAPI(hostname, hostname, portInt, ssl,
					false, null, null);

			try {
				SystemStatus status = new RootAPI(baseAPI).getStatus();
				return FormValidation
						.ok("Connection validated, running API version "
								+ status.getVersion());
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Invalid hostname, port or ssl");
			}
		}

		@Override
		public Publisher newInstance(StaplerRequest req, JSONObject formData)
				throws FormException {
			return super.newInstance(req, formData);
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		public String getHostname() {
			return hostname;
		}

		public void setHostname(String hostname) {
			this.hostname = hostname;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public boolean isSsl() {
			return ssl;
		}

		public void setSsl(boolean ssl) {
			this.ssl = ssl;
		}
	}
}
