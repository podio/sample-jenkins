package com.podio.hudson;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.Mailer;
import hudson.tasks.Mailer.UserProperty;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.joda.time.LocalDate;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.podio.BaseAPI;
import com.podio.common.Reference;
import com.podio.common.ReferenceType;
import com.podio.contact.ContactAPI;
import com.podio.contact.ProfileField;
import com.podio.contact.ProfileType;
import com.podio.item.FieldValues;
import com.podio.item.ItemAPI;
import com.podio.item.ItemCreate;
import com.podio.item.ItemsResponse;
import com.podio.oauth.OAuthClientCredentials;
import com.podio.oauth.OAuthUsernameCredentials;
import com.podio.root.RootAPI;
import com.podio.root.SystemStatus;
import com.podio.space.SpaceAPI;
import com.podio.space.SpaceWithOrganization;
import com.podio.task.Task;
import com.podio.task.TaskAPI;
import com.podio.task.TaskCreate;
import com.podio.task.TaskStatus;
import com.podio.user.UserMini;
import com.sun.jersey.api.client.UniformInterfaceException;

public class PodioBuildNotifier extends Notifier {

	@SuppressWarnings("unused")
	private static final Logger LOGGER = Logger
			.getLogger(PodioBuildNotifier.class.getName());

	private static final int CHANGES_FIELD_ID = 175133;

	private static final int FAILED_TESTS_FIELD_ID = 174816;

	private static final int TOTAL_TESTS_FIELD_ID = 92322;

	private static final int DEVELOPERS_FIELD_ID = 175134;

	private static final int URL_FIELD_ID = 174815;

	private static final int RESULT_FIELD_ID = 92321;

	private static final int BUILD_NUMBER_FIELD_ID = 92319;

	private static final int DURATION_FIELD_ID = 174817;

	private static final int APP_ID = 13658;

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
		BaseAPI baseAPI = getBaseAPI();

		String result = StringUtils.capitalize(build.getResult().toString()
				.toLowerCase());
		result = result.replace('_', ' ');
		SpaceWithOrganization space = getSpace(baseAPI);
		String url = Mailer.descriptor().getUrl() + build.getParent().getUrl()
				+ build.getNumber();
		Set<Integer> userIds = getUserIds(baseAPI, space.getId(), build);

		Integer totalTestCases = null;
		Integer failedTestCases = null;
		AbstractTestResultAction testResult = build.getTestResultAction();
		if (testResult != null) {
			totalTestCases = testResult.getTotalCount();
			failedTestCases = testResult.getFailCount();
		}

		String changes = getChangesText(build);

		int itemId = postBuild(baseAPI, build.getNumber(), result, url,
				changes, userIds, totalTestCases, failedTestCases,
				build.getDurationString());

		AbstractBuild previousBuild = build.getPreviousBuild();
		boolean oldFailed = previousBuild != null
				&& previousBuild.getResult() != Result.SUCCESS;

		TaskAPI taskAPI = new TaskAPI(baseAPI);
		if (oldFailed && build.getResult() == Result.SUCCESS) {
			Run firstFailed = getFirstFailure(previousBuild);
			Integer firstFailedItemId = getItemId(baseAPI,
					firstFailed.getNumber());
			if (firstFailedItemId != null) {
				List<Task> tasks = taskAPI.getTasksWithReference(new Reference(
						ReferenceType.ITEM, firstFailedItemId));
				for (Task task : tasks) {
					if (task.getStatus() == TaskStatus.ACTIVE) {
						taskAPI.completeTask(task.getId());
					}
				}
			}
		} else if (!oldFailed && build.getResult() != Result.SUCCESS) {
			for (Integer userId : userIds) {
				taskAPI.createTaskWithReference(new TaskCreate(
						"Fix broken build", false, new LocalDate(), userId),
						new Reference(ReferenceType.ITEM, itemId));
			}
		}

		return true;
	}

	private Run getFirstFailure(Run build) {
		Run previousBuild = build.getPreviousBuild();

		if (previousBuild != null) {
			if (previousBuild.getResult() == Result.SUCCESS) {
				return build;
			}

			return getFirstFailure(previousBuild);
		} else {
			return build;
		}
	}

	private Integer getItemId(BaseAPI baseAPI, int buildNumber) {
		ItemsResponse response = new ItemAPI(baseAPI).getItemsByExternalId(
				APP_ID, Integer.toString(buildNumber));
		if (response.getFiltered() != 1) {
			return null;
		}

		return response.getItems().get(0).getId();
	}

	private int postBuild(BaseAPI baseAPI, int buildNumber, String result,
			String url, String changes, Set<Integer> userIds,
			Integer totalTestCases, Integer failedTestCases, String duration) {
		List<FieldValues> fields = new ArrayList<FieldValues>();
		fields.add(new FieldValues(BUILD_NUMBER_FIELD_ID, "value", "Build "
				+ buildNumber));
		fields.add(new FieldValues(RESULT_FIELD_ID, "value", result));
		fields.add(new FieldValues(URL_FIELD_ID, "value", url));
		if (changes != null) {
			fields.add(new FieldValues(CHANGES_FIELD_ID, "value", changes));
		}
		List<Map<String, Object>> subValues = new ArrayList<Map<String, Object>>();
		for (Integer userId : userIds) {
			subValues.add(Collections.<String, Object> singletonMap("value",
					userId));
		}
		fields.add(new FieldValues(DEVELOPERS_FIELD_ID, subValues));
		if (totalTestCases != null) {
			fields.add(new FieldValues(TOTAL_TESTS_FIELD_ID, "value",
					totalTestCases));
		}
		if (failedTestCases != null) {
			fields.add(new FieldValues(FAILED_TESTS_FIELD_ID, "value",
					failedTestCases));
		}
		fields.add(new FieldValues(DURATION_FIELD_ID, "value", duration));
		ItemCreate create = new ItemCreate(Integer.toString(buildNumber),
				fields, Collections.<Integer> emptyList(),
				Collections.<String> emptyList());

		int itemId = new ItemAPI(baseAPI).addItem(APP_ID, create, true)
				.getItemId();

		return itemId;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	private SpaceWithOrganization getSpace(BaseAPI baseAPI) {
		return new SpaceAPI(baseAPI).getByURL(spaceURL);
	}

	private Set<Integer> getUserIds(BaseAPI baseAPI, int spaceId,
			AbstractBuild<?, ?> build) {
		Set<Integer> userIds = new HashSet<Integer>();

		Set<User> culprits = build.getCulprits();
		if (culprits.size() > 0) {
			for (User culprit : culprits) {
				Integer userId = getUserId(baseAPI, spaceId, culprit);
				if (userId != null) {
					userIds.add(userId);
				}
			}
		}
		ChangeLogSet<? extends Entry> changeSet = build.getChangeSet();
		if (changeSet != null) {
			for (Entry entry : changeSet) {
				Integer userId = getUserId(baseAPI, spaceId, entry.getAuthor());
				if (userId != null) {
					userIds.add(userId);
				}
			}
		}

		return userIds;
	}

	private String getChangesText(AbstractBuild<?, ?> build) {
		ChangeLogSet<? extends Entry> changeSet = build.getChangeSet();
		if (changeSet == null || changeSet.isEmptySet()) {
			return null;
		}

		String out = "";
		for (Entry entry : changeSet) {
			if (out.length() > 0) {
				out += "\n";
			}

			out += entry.getMsgAnnotated();
		}

		return out;
	}

	private Integer getUserId(BaseAPI baseAPI, int spaceId, User user) {
		UserProperty mailProperty = user.getProperty(Mailer.UserProperty.class);
		if (mailProperty == null) {
			return null;
		}
		String mail = mailProperty.getAddress();
		if (mail == null) {
			return null;
		}

		List<UserMini> contacts = new ContactAPI(baseAPI).getSpaceContacts(
				spaceId, ProfileField.MAIL, mail, 1, null, ProfileType.MINI,
				null);
		if (contacts.isEmpty()) {
			return null;
		}

		return contacts.get(0).getId();
	}

	@Extension
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		private String hostname = "api.podio.com";

		private int port = 443;

		private boolean ssl = true;

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
			this.hostname = formData.getString("hostname");
			this.port = formData.getInt("port");
			this.ssl = formData.getBoolean("ssl");
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
