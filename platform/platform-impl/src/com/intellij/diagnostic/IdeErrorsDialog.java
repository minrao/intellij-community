// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic;

import com.intellij.CommonBundle;
import com.intellij.ExtensionPoints;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.PluginManagerMain;
import com.intellij.ide.plugins.cl.PluginClassLoader;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.*;
import com.intellij.openapi.extensions.ExtensionException;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.zip.CRC32;

import static java.awt.GridBagConstraints.*;

public class IdeErrorsDialog extends DialogWrapper implements MessagePoolListener, DataProvider {
  private static final Logger LOG = Logger.getInstance(IdeErrorsDialog.class);

  public static final DataKey<String> CURRENT_TRACE_KEY = DataKey.create("current_stack_trace_key");

  private static List<Developer> ourDevelopersList = Collections.emptyList();

  private final MessagePool myMessagePool;
  private final Project myProject;
  private final boolean myInternalMode;
  private final List<AbstractMessage> myRawMessages = new ArrayList<>();
  private final List<List<AbstractMessage>> myMergedMessages = new ArrayList<>();
  private int myIndex;

  private JLabel myCountLabel;
  private HyperlinkLabel.Croppable myInfoLabel;
  private HyperlinkLabel.Croppable myDisableLink;
  private HyperlinkLabel.Croppable myForeignPluginWarningLabel;
  private JTextArea myCommentArea;
  private AttachmentsList myAttachmentsList;
  private JTextArea myAttachmentArea;
  private JPanel myAssigneePanel;
  private ComboBox<Developer> myAssigneeCombo;
  private HyperlinkLabel myCredentialsLabel;

  public IdeErrorsDialog(@NotNull MessagePool messagePool, @Nullable Project project, @Nullable LogMessage defaultMessage) {
    super(project, true);
    myMessagePool = messagePool;
    myProject = project;
    myInternalMode = ApplicationManager.getApplication().isInternal();

    setTitle(DiagnosticBundle.message("error.list.title"));
    setModal(false);
    init();
    setCancelButtonText(CommonBundle.message("close.action.name"));

    if (myInternalMode) {
      loadDevelopersList();
    }

    updateMessages();
    selectMessage(defaultMessage);
    updateControls();

    messagePool.addListener(this);
  }

  private void loadDevelopersList() {
    if (!ourDevelopersList.isEmpty()) {
      myAssigneeCombo.setModel(new CollectionComboBoxModel<>(ourDevelopersList));
    }
    else {
      new Task.Backgroundable(null, "Loading Developers List", true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          try {
            List<Developer> developers = ITNProxy.fetchDevelopers(indicator);
            //noinspection AssignmentToStaticFieldFromInstanceMethod
            ourDevelopersList = developers;
            UIUtil.invokeLaterIfNeeded(() -> {
              if (isShowing()) {
                myAssigneeCombo.setModel(new CollectionComboBoxModel<>(developers));
              }
            });
          }
          catch (IOException e) {
            LOG.warn(e);
          }
        }
      }.queue();
    }
  }

  private void selectMessage(@Nullable LogMessage defaultMessage) {
    for (int i = 0; i < myMergedMessages.size(); i++) {
      AbstractMessage message = myMergedMessages.get(i).get(0);
      if (defaultMessage != null && message == defaultMessage || defaultMessage == null && !message.isRead()) {
        myIndex = i;
        return;
      }
    }
  }

  @Nullable
  @Override
  protected JComponent createNorthPanel() {
    myCountLabel = new JBLabel();
    myInfoLabel = new HyperlinkLabel.Croppable();

    myDisableLink = new HyperlinkLabel.Croppable();
    myDisableLink.setHyperlinkText(UIUtil.removeMnemonic(DiagnosticBundle.message("error.list.disable.plugin")));
    myDisableLink.addHyperlinkListener(e -> {
      if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
        disablePlugin();
      }
    });

    myForeignPluginWarningLabel = new HyperlinkLabel.Croppable();

    JPanel controls = new JPanel(new BorderLayout());
    controls.add(actionToolbar("IdeErrorsBack", new BackAction()), BorderLayout.WEST);
    controls.add(myCountLabel, BorderLayout.CENTER);
    controls.add(actionToolbar("IdeErrorsForward", new ForwardAction()), BorderLayout.EAST);

    JPanel panel = new JPanel(new GridBagLayout());
    panel.add(controls, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, CENTER, NONE, JBUI.insets(2), 0, 0));
    panel.add(myInfoLabel, new GridBagConstraints(1, 0, 1, 1, 0.0, 0.0, WEST, NONE, JBUI.emptyInsets(), 0, 0));
    panel.add(myDisableLink, new GridBagConstraints(2, 0, 1, 1, 0.0, 0.0, WEST, NONE, JBUI.emptyInsets(), 0, 0));
    panel.add(new JPanel(), new GridBagConstraints(3, 0, 1, 1, 1.0, 0.0, CENTER, BOTH, JBUI.emptyInsets(), 0, 0));  // expander
    panel.add(myForeignPluginWarningLabel, new GridBagConstraints(1, 1, 3, 1, 0.0, 0.0, WEST, NONE, JBUI.emptyInsets(), 0, 0));
    return panel;
  }

  private static JComponent actionToolbar(String id, AnAction action) {
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(id, new DefaultActionGroup(action), true);
    toolbar.setLayoutPolicy(ActionToolbar.NOWRAP_LAYOUT_POLICY);
    toolbar.getComponent().setBorder(JBUI.Borders.empty());
    return toolbar.getComponent();
  }

  @Override
  protected JComponent createCenterPanel() {
    JBLabel commentLabel = new JBLabel(DiagnosticBundle.message("error.dialog.comment.prompt"));

    myCommentArea = new JTextArea(5, 0);
    myCommentArea.setMargin(JBUI.insets(2));
    myCommentArea.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        selectedMessage().setAdditionalInfo(myCommentArea.getText().trim());
      }
    });

    JBLabel attachmentsLabel = new JBLabel(DiagnosticBundle.message("error.dialog.attachments.prompt"));

    Dimension listSize = JBUI.size(150, 400), areaSize = JBUI.size(500, 400);

    myAttachmentsList = new AttachmentsList();
    myAttachmentsList.setMinimumSize(listSize);
    myAttachmentsList.addListSelectionListener(e -> {
      int index = myAttachmentsList.getSelectedIndex();
      if (index < 0) {
        myAttachmentArea.setText("");
      }
      else if (index == 0) {
        myAttachmentArea.setText(getDetailsText(selectedMessage()));
      }
      else {
        myAttachmentArea.setText(selectedMessage().getAllAttachments().get(index - 1).getDisplayText());
      }
      myAttachmentArea.moveCaretPosition(0);
    });
    myAttachmentsList.setCheckBoxListListener((index, value) -> {
      if (index > 0) {
        selectedMessage().getAllAttachments().get(index - 1).setIncluded(value);
      }
    });

    myAttachmentArea = new JTextArea();
    myAttachmentArea.setMinimumSize(areaSize);
    myAttachmentArea.setPreferredSize(areaSize);
    myAttachmentArea.setMargin(JBUI.insets(2));
    myAttachmentArea.setEditable(false);

    if (myInternalMode) {
      myAssigneeCombo = new ComboBox<>();
      myAssigneeCombo.setRenderer(new ListCellRendererWrapper<Developer>() {
        @Override
        public void customize(JList list, Developer value, int index, boolean selected, boolean hasFocus) {
          setText(value == null ? "<none>" : value.getDisplayText());
        }
      });
      myAssigneeCombo.setPrototypeDisplayValue(new Developer(0, StringUtil.repeatSymbol('-', 30)));
      myAssigneeCombo.addItemListener(e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
          Developer developer = (Developer)e.getItem();
          selectedMessage().setAssigneeId(developer == null ? null : developer.getId());
        }
      });
      new ComboboxSpeedSearch(myAssigneeCombo) {
        @Override
        protected String getElementText(Object element) {
          return element == null ? "" : ((Developer)element).getDisplayText();
        }
      };

      myAssigneePanel = new JPanel();
      myAssigneePanel.add(new JBLabel("Assignee:"));
      myAssigneePanel.add(myAssigneeCombo);
    }

    myCredentialsLabel = new HyperlinkLabel();
    myCredentialsLabel.addHyperlinkListener(e -> {
      if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
        JetBrainsAccountDialogKt.showJetBrainsAccountDialog(getRootPane()).show();
        updateCredentialsPanel(getSubmitter(selectedMessage().getThrowable()));
      }
    });

    JPanel commentPanel = new JPanel(new BorderLayout());
    commentPanel.setBorder(JBUI.Borders.emptyTop(5));
    commentPanel.add(commentLabel, BorderLayout.NORTH);
    commentPanel.add(scrollPane(myCommentArea), BorderLayout.CENTER);

    JPanel attachmentsPanel = new JPanel(new BorderLayout(JBUI.scale(5), 0));
    attachmentsPanel.setBorder(JBUI.Borders.emptyTop(5));
    attachmentsPanel.add(attachmentsLabel, BorderLayout.NORTH);
    attachmentsPanel.add(scrollPane(myAttachmentsList), BorderLayout.WEST);
    attachmentsPanel.add(scrollPane(myAttachmentArea), BorderLayout.CENTER);

    JPanel bottomRow = new JPanel(new BorderLayout());
    if (myInternalMode) {
      bottomRow.add(myAssigneePanel, BorderLayout.WEST);
    }
    bottomRow.add(myCredentialsLabel, BorderLayout.EAST);

    JPanel rootPanel = new JPanel(new BorderLayout());
    rootPanel.add(commentPanel, BorderLayout.NORTH);
    rootPanel.add(attachmentsPanel, BorderLayout.CENTER);
    rootPanel.add(bottomRow, BorderLayout.SOUTH);
    return rootPanel;
  }

  private static JScrollPane scrollPane(JComponent component) {
    JScrollPane scrollPane = new JBScrollPane(component);
    scrollPane.setMinimumSize(component.getMinimumSize());
    return scrollPane;
  }

  @NotNull
  @Override
  protected Action[] createActions() {
    List<Action> actions = new ArrayList<>();
    if (myInternalMode && myProject != null && !myProject.isDefault()) {
      AnAction action = ActionManager.getInstance().getAction("AnalyzeStacktraceOnError");
      if (action != null) {
        actions.add(new AnalyzeAction(action));
      }
    }
    actions.add(new ClearErrorsAction());
    actions.add(getOKAction());
    actions.add(getCancelAction());
    return actions.toArray(new Action[0]);
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myCommentArea;
  }

  @Override
  protected String getDimensionServiceKey() {
    return "IDE.errors.dialog";
  }

  @Override
  public void doOKAction() {
    if (getOKAction().isEnabled()) {
      boolean closeDialog = myMergedMessages.size() == 1;
      boolean reportingStarted = reportMessage(selectedMessage(), closeDialog);
      if (!closeDialog) {
        updateControls();
      }
      else if (reportingStarted) {
        super.doOKAction();
      }
    }
  }

  @Override
  protected void dispose() {
    myMessagePool.removeListener(this);
    super.dispose();
  }

  private AbstractMessage selectedMessage() {
    return myMergedMessages.get(myIndex).get(0);
  }

  private void updateMessages() {
    myRawMessages.clear();
    myRawMessages.addAll(myMessagePool.getFatalErrors(true, true));

    Map<Long, List<AbstractMessage>> messageGroups = new LinkedHashMap<>();
    for (AbstractMessage message : myRawMessages) {
      CRC32 digest = new CRC32();
      digest.update(StringUtil.getThrowableText(message.getThrowable()).getBytes(StandardCharsets.UTF_8));
      messageGroups.computeIfAbsent(digest.getValue(), k -> new ArrayList<>()).add(message);
    }
    myMergedMessages.clear();
    myMergedMessages.addAll(messageGroups.values());
  }

  private void updateControls() {
    myMergedMessages.get(myIndex).forEach(m -> m.setRead(true));
    AbstractMessage message = selectedMessage();
    ErrorReportSubmitter submitter = getSubmitter(message.getThrowable());

    updateLabels(message, submitter);

    updateDetails(message);

    if (myInternalMode) {
      updateAssigneePanel(message, submitter);
    }

    updateCredentialsPanel(submitter);

    setOKActionEnabled(submitter != null && !(message.isSubmitted() || message.isSubmitting()));
    setOKButtonText(submitter != null ? submitter.getReportActionText() : DiagnosticBundle.message("error.report.to.jetbrains.action"));
  }

  private void updateLabels(AbstractMessage message, @Nullable ErrorReportSubmitter submitter) {
    myCountLabel.setText(DiagnosticBundle.message("error.list.message.index.count", myIndex + 1, myMergedMessages.size()));

    Throwable t = message.getThrowable();
    if (t instanceof MessagePool.TooManyErrorsException) {
      myInfoLabel.setText(message.getMessage());
      myDisableLink.setVisible(false);
      myForeignPluginWarningLabel.setVisible(false);
      return;
    }

    PluginId pluginId = findPluginId(t);
    IdeaPluginDescriptor plugin = PluginManager.getPlugin(pluginId);

    StringBuilder info = new StringBuilder();
    String url = null;

    if (pluginId != null) {
      info.append(DiagnosticBundle.message("error.list.message.blame.plugin", plugin != null ? plugin.getName() : pluginId));
    }
    else if (t instanceof AbstractMethodError) {
      info.append(DiagnosticBundle.message("error.list.message.blame.unknown.plugin"));
    }
    else {
      info.append(DiagnosticBundle.message("error.list.message.blame.core", ApplicationNamesInfo.getInstance().getProductName()));
    }

    String date = DateFormatUtil.formatPrettyDateTime(message.getDate());
    int count = myMergedMessages.get(myIndex).size();
    info.append(' ').append(DiagnosticBundle.message("error.list.message.info", date, count));

    if (message.isSubmitted()) {
      SubmittedReportInfo submissionInfo = message.getSubmissionInfo();
      appendSubmissionInformation(submissionInfo, info);
      info.append('.');
      url = submissionInfo.getURL();
    }
    else if (message.isSubmitting()) {
      info.append(' ').append(DiagnosticBundle.message("error.list.message.submitting"));
    }

    myInfoLabel.setHtmlText(XmlStringUtil.wrapInHtml(info));
    myInfoLabel.setHyperlinkTarget(url);
    myInfoLabel.setToolTipText(url);

    myDisableLink.setVisible(pluginId != null && !ApplicationInfoEx.getInstanceEx().isEssentialPlugin(pluginId.getIdString()));

    if (submitter != null || plugin == null || PluginManagerMain.isDevelopedByJetBrains(plugin)) {
      myForeignPluginWarningLabel.setVisible(false);
    }
    else {
      myForeignPluginWarningLabel.setVisible(true);
      String vendor = plugin.getVendor();
      String contactUrl = plugin.getVendorUrl();
      String contactEmail = plugin.getVendorEmail();
      if (!StringUtil.isEmpty(vendor) && !StringUtil.isEmpty(contactUrl)) {
        myForeignPluginWarningLabel.setHtmlText(DiagnosticBundle.message("error.dialog.foreign.plugin.warning.vendor", vendor));
        myForeignPluginWarningLabel.setHyperlinkTarget(contactUrl);
      }
      else if (!StringUtil.isEmpty(contactUrl)) {
        myForeignPluginWarningLabel.setHtmlText(DiagnosticBundle.message("error.dialog.foreign.plugin.warning.unknown"));
        myForeignPluginWarningLabel.setHyperlinkTarget(contactUrl);
      }
      else if (!StringUtil.isEmpty(contactEmail)) {
        contactEmail = StringUtil.trimStart(contactEmail, " mailto:");
        myForeignPluginWarningLabel.setHtmlText(DiagnosticBundle.message("error.dialog.foreign.plugin.warning.vendor", contactEmail));
        myForeignPluginWarningLabel.setHyperlinkTarget("mailto:" + contactEmail);
      }
      else {
        myForeignPluginWarningLabel.setText(DiagnosticBundle.message("error.dialog.foreign.plugin.warning"));
        myForeignPluginWarningLabel.setHyperlinkTarget(null);
      }
      myForeignPluginWarningLabel.setToolTipText(contactUrl);
    }
  }

  private void updateDetails(AbstractMessage message) {
    myCommentArea.setText(message.getAdditionalInfo());
    myCommentArea.setEnabled(!(message.isSubmitted() || message.isSubmitting()));
    myCommentArea.setCaretPosition(0);

    myAttachmentsList.clear();
    myAttachmentsList.addItem("stacktrace.txt", true);
    for (Attachment attachment : message.getAllAttachments()) {
      myAttachmentsList.addItem(attachment.getName(), myInternalMode || attachment.isIncluded());
    }
    myAttachmentsList.setSelectedIndex(0);
  }

  private void updateAssigneePanel(AbstractMessage message, ErrorReportSubmitter submitter) {
    if (submitter instanceof ITNReporter) {
      myAssigneePanel.setVisible(true);
      myAssigneeCombo.setEnabled(!(message.isSubmitted() || message.isSubmitting()));
      Integer assignee = message.getAssigneeId();
      if (assignee == null) {
        myAssigneeCombo.setSelectedIndex(-1);
      }
      else {
        Condition<Developer> lookup = d -> Objects.equals(assignee, d.getId());
        myAssigneeCombo.setSelectedIndex(ContainerUtil.indexOf(ourDevelopersList, lookup));
      }
    }
    else {
      myAssigneePanel.setVisible(false);
    }
  }

  private void updateCredentialsPanel(ErrorReportSubmitter submitter) {
    if (submitter instanceof ITNReporter) {
      myCredentialsLabel.setVisible(true);
      Credentials credentials = ErrorReportConfigurable.getCredentials();
      if (CredentialAttributesKt.isFulfilled(credentials)) {
        myCredentialsLabel.setHtmlText(DiagnosticBundle.message("diagnostic.error.report.submit.report.as", credentials.getUserName()));
      }
      else {
        myCredentialsLabel.setHtmlText(DiagnosticBundle.message("diagnostic.error.report.submit.error.anonymously"));
      }
    }
    else {
      myCredentialsLabel.setVisible(false);
    }
  }

  private boolean reportMessage(AbstractMessage message, boolean dialogClosed) {
    ErrorReportSubmitter submitter = getSubmitter(message.getThrowable());
    if (submitter == null) return false;

    message.setSubmitting(true);

    IdeaLoggingEvent[] events;
    if (message instanceof GroupedLogMessage) {
      events = ((GroupedLogMessage)message).getMessages().stream().map(IdeErrorsDialog::getEvent).toArray(IdeaLoggingEvent[]::new);
    }
    else {
      events = new IdeaLoggingEvent[]{getEvent(message)};
    }

    Container parentComponent = getRootPane();
    if (dialogClosed) {
      IdeFrame frame = UIUtil.getParentOfType(IdeFrame.class, parentComponent);
      parentComponent = frame != null ? frame.getComponent() : WindowManager.getInstance().findVisibleFrame();
    }

    return submitter.submit(events, message.getAdditionalInfo(), parentComponent, reportInfo -> {
      message.setSubmitting(false);
      message.setSubmitted(reportInfo);
      ApplicationManager.getApplication().invokeLater(() -> updateOnSubmit());
    });
  }

  private static IdeaLoggingEvent getEvent(AbstractMessage message) {
    if (message instanceof LogMessageEx) {
      return ((LogMessageEx)message).toEvent();
    }
    else {
      return new IdeaLoggingEvent(message.getMessage(), message.getThrowable(), message);
    }
  }

  private void disablePlugin() {
    PluginId pluginId = findPluginId(selectedMessage().getThrowable());
    if (pluginId != null) {
      IdeaPluginDescriptor plugin = PluginManager.getPlugin(pluginId);
      if (plugin != null) {
        Ref<Boolean> hasDependants = new Ref<>(false);
        PluginManagerCore.checkDependants(plugin, PluginManager::getPlugin, dependantId -> {
          if (PluginManagerCore.CORE_PLUGIN_ID.equals(dependantId.getIdString())) {
            return true;
          }
          else {
            hasDependants.set(true);
            return false;
          }
        });
        boolean canRestart = ApplicationManager.getApplication().isRestartCapable();

        String message =
          "<html>" +
          DiagnosticBundle.message("error.dialog.disable.prompt", plugin.getName()) + "<br/>" +
          DiagnosticBundle.message(hasDependants.get() ? "error.dialog.disable.prompt.deps" : "error.dialog.disable.prompt.lone") + "<br/><br/>" +
          DiagnosticBundle.message(canRestart ? "error.dialog.disable.plugin.can.restart" : "error.dialog.disable.plugin.no.restart") +
          "</html>";
        String title = DiagnosticBundle.message("error.dialog.disable.plugin.title");
        String disable = DiagnosticBundle.message("error.dialog.disable.plugin.action.disable");
        String cancel = IdeBundle.message("button.cancel");

        boolean doDisable, doRestart;
        if (canRestart) {
          String restart = DiagnosticBundle.message("error.dialog.disable.plugin.action.disableAndRestart");
          int result = Messages.showYesNoCancelDialog(myProject, message, title, disable, restart, cancel, Messages.getQuestionIcon());
          doDisable = result == Messages.YES || result == Messages.NO;
          doRestart = result == Messages.NO;
        }
        else {
          int result = Messages.showYesNoDialog(myProject, message, title, disable, cancel, Messages.getQuestionIcon());
          doDisable = result == Messages.YES;
          doRestart = false;
        }

        if (doDisable) {
          PluginManagerCore.disablePlugin(pluginId.getIdString());
          if (doRestart) {
            ApplicationManager.getApplication().restart();
          }
        }
      }
    }
  }

  protected void updateOnSubmit() {
    if (isShowing()) {
      updateControls();
    }
  }

  /* UI components */

  private static class AttachmentsList extends CheckBoxList<String> {
    private void addItem(String item, boolean selected) {
      super.addItem(item, item + "  ", selected);
    }

    @Override
    protected boolean isEnabled(int index) {
      return index > 0;
    }
  }

  private class BackAction extends AnAction implements DumbAware {
    public BackAction() {
      super("Previous", null, AllIcons.Actions.Back);
      AnAction action = ActionManager.getInstance().getAction(IdeActions.ACTION_PREVIOUS_TAB);
      if (action != null) {
        registerCustomShortcutSet(action.getShortcutSet(), getRootPane(), getDisposable());
      }
    }

    @Override
    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(myIndex > 0);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      myIndex--;
      updateControls();
    }
  }

  private class ForwardAction extends AnAction implements DumbAware {
    public ForwardAction() {
      super("Next", null, AllIcons.Actions.Forward);
      AnAction action = ActionManager.getInstance().getAction(IdeActions.ACTION_NEXT_TAB);
      if (action != null) {
        registerCustomShortcutSet(action.getShortcutSet(), getRootPane(), getDisposable());
      }
    }

    @Override
    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(myIndex < myMergedMessages.size() - 1);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      myIndex++;
      updateControls();
    }
  }

  private class ClearErrorsAction extends AbstractAction {
    private ClearErrorsAction() {
      super(DiagnosticBundle.message("error.dialog.clear.all.action"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      myMessagePool.clearErrors();
      doCancelAction();
    }
  }

  private class AnalyzeAction extends AbstractAction {
    private final AnAction myAnalyze;

    private AnalyzeAction(AnAction analyze) {
      super(analyze.getTemplatePresentation().getText());
      putValue(Action.MNEMONIC_KEY, analyze.getTemplatePresentation().getMnemonic());
      myAnalyze = analyze;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      DataContext ctx = DataManager.getInstance().getDataContext((Component)e.getSource());
      AnActionEvent event = AnActionEvent.createFromAnAction(myAnalyze, null, ActionPlaces.UNKNOWN, ctx);
      myAnalyze.actionPerformed(event);
      doCancelAction();
    }
  }

  /* interfaces */

  @Override
  public void newEntryAdded() {
    UIUtil.invokeLaterIfNeeded(() -> {
      updateMessages();
      updateControls();
    });
  }

  @Override
  public void poolCleared() {
    UIUtil.invokeLaterIfNeeded(() -> doCancelAction());
  }

  @Override
  public void entryWasRead() { }

  @Override
  public Object getData(String dataId) {
    return CURRENT_TRACE_KEY.is(dataId) ? getDetailsText(selectedMessage()) : null;
  }

  /* helpers */

  private static String getDetailsText(AbstractMessage message) {
    Throwable t = message.getThrowable();
    return t instanceof MessagePool.TooManyErrorsException ? message.getMessage() :
           t instanceof NullPointerException ? message.getThrowableText() :
           message.getMessage() + "\n" + message.getThrowableText();
  }

  @Nullable
  public static PluginId findPluginId(@NotNull Throwable t) {
    if (t instanceof PluginException) {
      return ((PluginException)t).getPluginId();
    }

    Set<String> visitedClassNames = ContainerUtil.newHashSet();
    for (StackTraceElement element : t.getStackTrace()) {
      if (element != null) {
        String className = element.getClassName();
        if (visitedClassNames.add(className) && PluginManagerCore.isPluginClass(className)) {
          PluginId id = PluginManagerCore.getPluginByClassName(className);
          logPluginDetection(className, id);
          return id;
        }
      }
    }

    if (t instanceof NoSuchMethodException) {
      // check is method called from plugin classes
      if (t.getMessage() != null) {
        StringBuilder className = new StringBuilder();
        StringTokenizer tok = new StringTokenizer(t.getMessage(), ".");
        while (tok.hasMoreTokens()) {
          String token = tok.nextToken();
          if (!token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0))) {
            className.append(token);
          }
        }

        PluginId pluginId = PluginManagerCore.getPluginByClassName(className.toString());
        if (pluginId != null) {
          return pluginId;
        }
      }
    }
    else if (t instanceof ClassNotFoundException) {
      // check is class from plugin classes
      if (t.getMessage() != null) {
        String className = t.getMessage();

        if (PluginManagerCore.isPluginClass(className)) {
          return PluginManagerCore.getPluginByClassName(className);
        }
      }
    }
    else if (t instanceof AbstractMethodError && t.getMessage() != null) {
      String s = t.getMessage();
      int pos = s.indexOf('(');
      if (pos >= 0) {
        s = s.substring(0, pos);
        pos = s.lastIndexOf('.');
        if (pos >= 0) {
          s = s.substring(0, pos);
          if (PluginManagerCore.isPluginClass(s)) {
            return PluginManagerCore.getPluginByClassName(s);
          }
        }
      }
    }
    else if (t instanceof ExtensionException) {
      String className = ((ExtensionException)t).getExtensionClass().getName();
      if (PluginManagerCore.isPluginClass(className)) {
        return PluginManagerCore.getPluginByClassName(className);
      }
    }

    return null;
  }

  private static void logPluginDetection(String className, PluginId id) {
    if (LOG.isDebugEnabled()) {
      String message = "Detected plugin " + id + " by class " + className;
      IdeaPluginDescriptor descriptor = PluginManager.getPlugin(id);
      if (descriptor != null) {
        ClassLoader loader = descriptor.getPluginClassLoader();
        message += "; loader=" + loader + '/' + loader.getClass();
        if (loader instanceof PluginClassLoader) {
          message += "; loaded class: " + ((PluginClassLoader)loader).hasLoadedClass(className);
        }
      }
      LOG.debug(message);
    }
  }

  @Nullable
  static ErrorReportSubmitter getSubmitter(@NotNull Throwable t) {
    if (t instanceof MessagePool.TooManyErrorsException || t instanceof AbstractMethodError) {
      return null;
    }

    ErrorReportSubmitter[] reporters;
    try {
      reporters = Extensions.getExtensions(ExtensionPoints.ERROR_HANDLER_EP);
    }
    catch (Throwable ignored) {
      return null;
    }

    PluginId pluginId = findPluginId(t);
    IdeaPluginDescriptor plugin = PluginManager.getPlugin(pluginId);

    if (plugin != null) {
      for (ErrorReportSubmitter reporter : reporters) {
        PluginDescriptor descriptor = reporter.getPluginDescriptor();
        if (descriptor != null && Comparing.equal(pluginId, descriptor.getPluginId())) {
          return reporter;
        }
      }
    }

    if (plugin == null || PluginManagerMain.isDevelopedByJetBrains(plugin)) {
      for (ErrorReportSubmitter reporter : reporters) {
        PluginDescriptor descriptor = reporter.getPluginDescriptor();
        if (descriptor == null || PluginId.getId(PluginManagerCore.CORE_PLUGIN_ID) == descriptor.getPluginId()) {
          return reporter;
        }
      }
    }

    return null;
  }

  public static void appendSubmissionInformation(@NotNull SubmittedReportInfo info, @NotNull StringBuilder out) {
    if (info.getStatus() == SubmittedReportInfo.SubmissionStatus.FAILED) {
      out.append(' ').append(DiagnosticBundle.message("error.list.message.submission.failed"));
    }
    else if (info.getURL() != null && info.getLinkText() != null) {
      out.append(' ').append(DiagnosticBundle.message("error.list.message.submitted.as.link", info.getURL(), info.getLinkText()));
      if (info.getStatus() == SubmittedReportInfo.SubmissionStatus.DUPLICATE) {
        out.append(' ').append(DiagnosticBundle.message("error.list.message.duplicate"));
      }
    }
    else {
      out.append(' ').append(DiagnosticBundle.message("error.list.message.submitted"));
    }
  }
}