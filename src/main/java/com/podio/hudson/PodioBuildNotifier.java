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

import com.podio.APIFactory;
import com.podio.ResourceFactory;
import com.podio.app.Application;
import com.podio.common.Reference;
import com.podio.common.ReferenceType;
import com.podio.contact.ProfileField;
import com.podio.contact.ProfileMini;
import com.podio.contact.ProfileType;
import com.podio.item.FieldValuesUpdate;
import com.podio.item.ItemCreate;
import com.podio.item.ItemsResponse;
import com.podio.oauth.OAuthClientCredentials;
import com.podio.oauth.OAuthUsernameCredentials;
import com.podio.task.Task;
import com.podio.task.TaskAPI;
import com.podio.task.TaskCreate;
import com.podio.task.TaskStatus;
import com.sun.jersey.api.client.UniformInterfaceException;

public class PodioBuildNotifier extends Notifier {

	@SuppressWarnings("unused")
	private static final Logger LOGGER = Logger
			.getLogger(PodioBuildNotifier.class.getName());

	private final String appId;

	@DataBoundConstructor
	public PodioBuildNotifier(String appId) {
		this.appId = appId;
	}

	public String getAppId() {
		return appId;
	}

	private APIFactory getBaseAPI() {
		DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();

		return new APIFactory(new ResourceFactory(new OAuthClientCredentials(
				descriptor.clientId, descriptor.clientSecret),
				new OAuthUsernameCredentials(descriptor.username,
						descriptor.password)));
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		APIFactory apiFactory = getBaseAPI();

		String result = StringUtils.capitalize(build.getResult().toString()
				.toLowerCase());
		result = result.replace('_', ' ');
		int spaceId = getSpace(apiFactory);
		String url = Mailer.descriptor().getUrl() + build.getParent().getUrl()
				+ build.getNumber();
		Set<ProfileMini> profiles = getProfiles(apiFactory, spaceId, build);

		Integer totalTestCases = null;
		Integer failedTestCases = null;
		AbstractTestResultAction testResult = build.getTestResultAction();
		if (testResult != null) {
			totalTestCases = testResult.getTotalCount();
			failedTestCases = testResult.getFailCount();
		}

		String changes = getChangesText(build);

		int itemId = postBuild(apiFactory, build.getNumber(), result, url,
				changes, profiles, totalTestCases, failedTestCases,
				build.getDurationString());

		AbstractBuild previousBuild = build.getPreviousBuild();
		boolean oldFailed = previousBuild != null
				&& previousBuild.getResult() != Result.SUCCESS;

		TaskAPI taskAPI = apiFactory.getTaskAPI();
		if (oldFailed && build.getResult() == Result.SUCCESS) {
			Run firstFailed = getFirstFailure(previousBuild);
			Integer firstFailedItemId = getItemId(apiFactory,
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
			String text = "Build " + build.getNumber()
					+ " did not succeed with the result "
					+ build.getResult().toString().toLowerCase() + ". ";
			if (testResult != null && testResult.getFailCount() > 0) {
				text += testResult.getFailCount() + " testcase(s) failed. ";
			}
			text += "Please fix the build.";
			for (ProfileMini profile : profiles) {
				taskAPI.createTaskWithReference(new TaskCreate(text, false,
						new LocalDate(), profile.getUserId()), new Reference(
						ReferenceType.ITEM, itemId), true);
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

	private Integer getItemId(APIFactory apiFactory, int buildNumber) {
		ItemsResponse response = apiFactory.getItemAPI().getItemsByExternalId(
				Integer.parseInt(appId), Integer.toString(buildNumber));
		if (response.getFiltered() != 1) {
			return null;
		}

		return response.getItems().get(0).getId();
	}

	private int postBuild(APIFactory apiFactory, int buildNumber,
			String result, String url, String changes,
			Set<ProfileMini> profiles, Integer totalTestCases,
			Integer failedTestCases, String duration) {
		List<FieldValuesUpdate> fields = new ArrayList<FieldValuesUpdate>();
		fields.add(new FieldValuesUpdate("build-number", "value", "Build "
				+ buildNumber));
		fields.add(new FieldValuesUpdate("result", "value", result));
		fields.add(new FieldValuesUpdate("url", "value", url));
		if (changes != null) {
			fields.add(new FieldValuesUpdate("changes", "value", changes));
		}
		List<Map<String, ?>> subValues = new ArrayList<Map<String, ?>>();
		for (ProfileMini profile : profiles) {
			subValues.add(Collections.<String, Object> singletonMap("value",
					profile.getProfileId()));
		}
		fields.add(new FieldValuesUpdate("developers", subValues));
		if (totalTestCases != null) {
			fields.add(new FieldValuesUpdate("total-testcases", "value",
					totalTestCases));
		}
		if (failedTestCases != null) {
			fields.add(new FieldValuesUpdate("failed-testcases", "value",
					failedTestCases));
		}
		fields.add(new FieldValuesUpdate("duration", "value", duration));
		ItemCreate create = new ItemCreate(Integer.toString(buildNumber),
				fields, Collections.<Integer> emptyList(),
				Collections.<String> emptyList());

		int itemId = apiFactory.getItemAPI().addItem(Integer.parseInt(appId),
				create, true);

		return itemId;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	private int getSpace(APIFactory apiFactory) {
		return apiFactory.getAppAPI().getApp(Integer.parseInt(appId))
				.getSpaceId();
	}

	private Set<ProfileMini> getProfiles(APIFactory apiFactory, int spaceId,
			AbstractBuild<?, ?> build) {
		Set<ProfileMini> profiles = new HashSet<ProfileMini>();

		Set<User> culprits = build.getCulprits();
		if (culprits.size() > 0) {
			for (User culprit : culprits) {
				ProfileMini profile = getProfile(apiFactory, spaceId, culprit);
				if (profile != null) {
					profiles.add(profile);
				}
			}
		}
		ChangeLogSet<? extends Entry> changeSet = build.getChangeSet();
		if (changeSet != null) {
			for (Entry entry : changeSet) {
				ProfileMini profile = getProfile(apiFactory, spaceId,
						entry.getAuthor());
				if (profile != null) {
					profiles.add(profile);
				}
			}
		}

		return profiles;
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

	private ProfileMini getProfile(APIFactory apiFactory, int spaceId, User user) {
		UserProperty mailProperty = user.getProperty(Mailer.UserProperty.class);
		if (mailProperty == null) {
			return null;
		}
		String mail = mailProperty.getAddress();
		if (mail == null) {
			return null;
		}

		List<ProfileMini> contacts = apiFactory.getContactAPI()
				.getSpaceContacts(spaceId, ProfileField.MAIL, mail, 1, null,
						ProfileType.MINI, null);
		if (contacts.isEmpty()) {
			return null;
		}

		return contacts.get(0);
	}

	@Extension
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		private String username;

		private String password;

		private String clientId;

		private String clientSecret;

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
			this.username = formData.getString("username");
			this.password = formData.getString("password");
			this.clientId = formData.getString("clientId");
			this.clientSecret = formData.getString("clientSecret");
			save();
			return super.configure(req, formData);
		}

		public FormValidation doValidateAuth(
				@QueryParameter("appId") final String appId)
				throws IOException, ServletException {
			APIFactory apiFactory = new APIFactory(new ResourceFactory(
					new OAuthClientCredentials(clientId, clientSecret),
					new OAuthUsernameCredentials(username, password)));

			try {
				Application app = apiFactory.getAppAPI().getApp(
						Integer.parseInt(appId));
				return FormValidation.ok("Connection ok, using app "
						+ app.getConfiguration().getName());
			} catch (UniformInterfaceException e) {
				if (e.getResponse().getStatus() == 404) {
					return FormValidation.error("No app found with the id "
							+ appId);
				} else {
					return FormValidation.error("Invalid username or password");
				}
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Invalid username or password");
			}
		}

		public FormValidation doValidateAPI(
				@QueryParameter("username") final String username,
				@QueryParameter("password") final String password,
				@QueryParameter("clientId") final String clientId,
				@QueryParameter("clientSecret") final String clientSecret)
				throws IOException, ServletException {
			APIFactory baseAPI = new APIFactory(new ResourceFactory(
					new OAuthClientCredentials(clientId, clientSecret),
					new OAuthUsernameCredentials(username, password)));

			try {
				String name = baseAPI.getUserAPI().getProfile().getName();
				return FormValidation
						.ok("Connection validated, logged in as " + name);
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

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}
	}
}
