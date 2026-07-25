using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Hoshira Installer")]
[assembly: AssemblyDescription("Фирменный установщик Hoshira")]
[assembly: AssemblyCompany("Hoshira Community")]
[assembly: AssemblyProduct("Hoshira Installer")]
[assembly: AssemblyCopyright("Hoshira Community")]
[assembly: AssemblyVersion("0.2.6.0")]
[assembly: AssemblyFileVersion("0.2.6.0")]

namespace Hoshira.Setup
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }
    }

    internal sealed class InstallerForm : Form
    {
        private const string InstallerVersion = "0.2.6";
        private const string PayloadResourceName = "Hoshira.Payload.msi";
        private const string SetupIconResourceName = "Hoshira.SetupIcon";
        private const string WebView2BootstrapperResourceName =
            "Hoshira.WebView2Bootstrapper.exe";

        private static readonly Color Surface = Color.FromArgb(8, 9, 11);
        private static readonly Color Elevated = Color.FromArgb(18, 19, 23);
        private static readonly Color ElevatedHover = Color.FromArgb(27, 28, 33);
        private static readonly Color Border = Color.FromArgb(45, 47, 54);
        private static readonly Color PrimaryText = Color.FromArgb(247, 247, 249);
        private static readonly Color SecondaryText = Color.FromArgb(166, 169, 178);
        private static readonly Color Accent = Color.FromArgb(255, 78, 0);

        private readonly Label headingLabel;
        private readonly Label descriptionLabel;
        private readonly Label installStatusLabel;
        private readonly Label detailLabel;
        private readonly RoundedPanel pathPanel;
        private readonly TextBox pathTextBox;
        private readonly RoundedButton browseButton;
        private readonly RoundedButton primaryButton;
        private readonly RoundedButton secondaryButton;
        private readonly MarqueeBar progressBar;
        private readonly RoundedButton closeButton;
        private readonly RoundedButton minimizeButton;
        private readonly InstalledProduct installedProduct;

        private bool installationComplete;
        private bool installationInProgress;
        private string failureLogPath;

        internal InstallerForm()
        {
            installedProduct = InstalledProduct.Find();

            Text = "Установка Hoshira";
            ClientSize = new Size(860, 540);
            MinimumSize = new Size(860, 540);
            MaximumSize = new Size(860, 540);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.None;
            BackColor = Surface;
            ForeColor = PrimaryText;
            Font = new Font("Segoe UI", 10F, FontStyle.Regular, GraphicsUnit.Point);
            DoubleBuffered = true;
            KeyPreview = true;
            ShowIcon = true;
            ShowInTaskbar = true;
            LoadApplicationIcon();

            Panel titleBar = new Panel();
            titleBar.Dock = DockStyle.Top;
            titleBar.Height = 70;
            titleBar.BackColor = Surface;
            titleBar.MouseDown += TitleBarMouseDown;
            Controls.Add(titleBar);

            BrandGlyphControl logo = new BrandGlyphControl();
            logo.Location = new Point(30, 20);
            logo.Size = new Size(32, 32);
            logo.BackColor = Color.Transparent;
            titleBar.Controls.Add(logo);

            Label appName = CreateLabel("HOSHIRA", 74, 19, 132, 24, 14F, FontStyle.Bold, PrimaryText);
            Label installerName = CreateLabel("УСТАНОВЩИК", 75, 42, 132, 18, 8F, FontStyle.Bold, SecondaryText);
            titleBar.Controls.Add(appName);
            titleBar.Controls.Add(installerName);

            minimizeButton = new RoundedButton(
                "—",
                Elevated,
                ElevatedHover,
                PrimaryText,
                12,
                Surface);
            minimizeButton.Location = new Point(766, 18);
            minimizeButton.Size = new Size(34, 34);
            minimizeButton.Font = new Font("Segoe UI", 11F, FontStyle.Regular);
            minimizeButton.Click += delegate { WindowState = FormWindowState.Minimized; };
            titleBar.Controls.Add(minimizeButton);

            closeButton = new RoundedButton(
                "×",
                Elevated,
                Color.FromArgb(112, 35, 36),
                PrimaryText,
                12,
                Surface);
            closeButton.Location = new Point(810, 18);
            closeButton.Size = new Size(34, 34);
            closeButton.Font = new Font("Segoe UI", 15F, FontStyle.Regular);
            closeButton.Click += CloseButtonClick;
            titleBar.Controls.Add(closeButton);

            HeroPanel hero = new HeroPanel();
            hero.Location = new Point(550, 70);
            hero.Size = new Size(310, 470);
            hero.Anchor = AnchorStyles.Top | AnchorStyles.Right | AnchorStyles.Bottom;
            Controls.Add(hero);

            Label versionBadge = CreateLabel(
                "  ВЕРСИЯ " + InstallerVersion + "  ",
                52,
                98,
                122,
                25,
                8F,
                FontStyle.Bold,
                Color.FromArgb(255, 135, 76));
            versionBadge.TextAlign = ContentAlignment.MiddleCenter;
            versionBadge.BackColor = Color.FromArgb(43, 23, 17);
            Controls.Add(versionBadge);

            headingLabel = CreateLabel(
                "Добро пожаловать\nв Hoshira",
                50,
                143,
                480,
                92,
                27F,
                FontStyle.Bold,
                PrimaryText);
            headingLabel.AutoSize = false;
            Controls.Add(headingLabel);

            descriptionLabel = CreateLabel(
                BuildWelcomeDescription(),
                52,
                242,
                462,
                50,
                10F,
                FontStyle.Regular,
                SecondaryText);
            descriptionLabel.AutoSize = false;
            Controls.Add(descriptionLabel);

            Label pathCaption = CreateLabel(
                "ПАПКА УСТАНОВКИ",
                53,
                308,
                190,
                18,
                8F,
                FontStyle.Bold,
                SecondaryText);
            Controls.Add(pathCaption);

            pathPanel = new RoundedPanel(Elevated, Border, 15);
            pathPanel.Location = new Point(50, 333);
            pathPanel.Size = new Size(470, 56);
            Controls.Add(pathPanel);

            pathTextBox = new TextBox();
            pathTextBox.BorderStyle = BorderStyle.None;
            pathTextBox.BackColor = Elevated;
            pathTextBox.ForeColor = PrimaryText;
            pathTextBox.Font = new Font("Segoe UI", 10F, FontStyle.Regular);
            pathTextBox.Location = new Point(17, 19);
            pathTextBox.Size = new Size(350, 22);
            pathTextBox.Text = GetDefaultInstallPath();
            pathPanel.Controls.Add(pathTextBox);

            browseButton = new RoundedButton(
                "Изменить",
                Color.FromArgb(32, 34, 40),
                ElevatedHover,
                PrimaryText,
                12,
                Elevated);
            browseButton.Location = new Point(377, 10);
            browseButton.Size = new Size(84, 36);
            browseButton.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            browseButton.Click += BrowseButtonClick;
            pathPanel.Controls.Add(browseButton);

            detailLabel = CreateLabel(
                "Ярлыки появятся на рабочем столе и в меню «Пуск».",
                53,
                402,
                455,
                23,
                9F,
                FontStyle.Regular,
                SecondaryText);
            Controls.Add(detailLabel);

            progressBar = new MarqueeBar();
            progressBar.Location = new Point(50, 441);
            progressBar.Size = new Size(470, 5);
            progressBar.Visible = false;
            Controls.Add(progressBar);

            installStatusLabel = CreateLabel(
                "",
                52,
                457,
                466,
                25,
                9F,
                FontStyle.Regular,
                SecondaryText);
            installStatusLabel.Visible = false;
            Controls.Add(installStatusLabel);

            primaryButton = new RoundedButton(
                installedProduct == null ? "Установить" : "Обновить",
                Accent,
                Color.FromArgb(255, 99, 29),
                Color.White,
                17,
                Surface);
            primaryButton.Location = new Point(50, 467);
            primaryButton.Size = new Size(222, 52);
            primaryButton.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            primaryButton.Click += PrimaryButtonClick;
            Controls.Add(primaryButton);

            secondaryButton = new RoundedButton(
                "Закрыть",
                Elevated,
                ElevatedHover,
                PrimaryText,
                17,
                Surface);
            secondaryButton.Location = new Point(284, 467);
            secondaryButton.Size = new Size(144, 52);
            secondaryButton.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            secondaryButton.Visible = false;
            secondaryButton.Click += delegate { Close(); };
            Controls.Add(secondaryButton);

            FormClosing += InstallerFormClosing;
            Resize += delegate { ApplyRoundedRegion(); };
            Shown += delegate
            {
                ApplyRoundedRegion();
                UpdatePrerequisiteStatus();
                primaryButton.Focus();
            };
        }

        protected override CreateParams CreateParams
        {
            get
            {
                const int CsDropShadow = 0x00020000;
                CreateParams parameters = base.CreateParams;
                parameters.ClassStyle |= CsDropShadow;
                return parameters;
            }
        }

        private string BuildWelcomeDescription()
        {
            if (installedProduct == null)
            {
                return "Установщик подготовит приложение и все необходимые компоненты.";
            }

            if (CompareVersions(installedProduct.Version, InstallerVersion) == 0)
            {
                return "Версия " + installedProduct.Version +
                    " уже установлена. Она будет аккуратно переустановлена.";
            }

            return "Найдена Hoshira " + installedProduct.Version +
                ". Настройки и данные аккаунта сохранятся после обновления.";
        }

        private void UpdatePrerequisiteStatus()
        {
            string webView2Version = WebView2Runtime.FindVersion();
            detailLabel.Top = 395;
            detailLabel.Height = 42;
            detailLabel.Text = String.IsNullOrEmpty(webView2Version)
                ? "WebView2 Runtime не найден — установим официальный компонент Microsoft."
                : "Системные компоненты готовы. Ярлыки появятся на рабочем столе и в меню «Пуск».";
        }

        private void LoadApplicationIcon()
        {
            try
            {
                using (Stream stream = Assembly.GetExecutingAssembly()
                    .GetManifestResourceStream(SetupIconResourceName))
                {
                    if (stream == null)
                    {
                        return;
                    }

                    using (Icon embeddedIcon = new Icon(stream))
                    {
                        Icon = (Icon)embeddedIcon.Clone();
                    }
                }
            }
            catch
            {
                // The native executable icon remains available as a fallback.
            }
        }

        private string GetDefaultInstallPath()
        {
            if (installedProduct != null &&
                !String.IsNullOrWhiteSpace(installedProduct.InstallLocation))
            {
                return installedProduct.InstallLocation.TrimEnd(Path.DirectorySeparatorChar);
            }

            string programFiles = Environment.GetEnvironmentVariable("ProgramW6432");
            if (String.IsNullOrWhiteSpace(programFiles))
            {
                programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            }
            return Path.Combine(programFiles, "Hoshira");
        }

        private void BrowseButtonClick(object sender, EventArgs eventArgs)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Выберите папку для установки Hoshira";
                dialog.SelectedPath = Directory.Exists(pathTextBox.Text)
                    ? pathTextBox.Text
                    : Path.GetDirectoryName(pathTextBox.Text);

                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    pathTextBox.Text = Path.Combine(dialog.SelectedPath, "Hoshira");
                }
            }
        }

        private void PrimaryButtonClick(object sender, EventArgs eventArgs)
        {
            if (installationComplete)
            {
                LaunchInstalledApplication();
                return;
            }

            if (!String.IsNullOrEmpty(failureLogPath))
            {
                try
                {
                    Process.Start("notepad.exe", Quote(failureLogPath));
                }
                catch (Exception exception)
                {
                    ShowInstallerMessage("Не удалось открыть журнал", exception.Message);
                }
                return;
            }

            if (installationInProgress)
            {
                return;
            }

            string installPath;
            try
            {
                installPath = Path.GetFullPath(pathTextBox.Text.Trim());
            }
            catch
            {
                ShowInstallerMessage(
                    "Неверная папка",
                    "Укажите корректный полный путь для установки Hoshira.");
                return;
            }

            if (String.IsNullOrWhiteSpace(pathTextBox.Text) ||
                String.Equals(Path.GetPathRoot(installPath), installPath, StringComparison.OrdinalIgnoreCase))
            {
                ShowInstallerMessage(
                    "Неверная папка",
                    "Hoshira нельзя устанавливать непосредственно в корень диска.");
                return;
            }

            Process[] runningApplications = Process.GetProcessesByName("Hoshira");
            if (runningApplications.Length > 0)
            {
                foreach (Process runningApplication in runningApplications)
                {
                    runningApplication.Dispose();
                }
                ShowInstallerMessage(
                    "Hoshira запущена",
                    "Закройте приложение перед установкой обновления.");
                return;
            }

            if (installedProduct != null &&
                CompareVersions(installedProduct.Version, InstallerVersion) > 0)
            {
                ShowInstallerMessage(
                    "Установлена более новая версия",
                    "Версия " + installedProduct.Version +
                    " новее этого установщика. Понижение версии отменено.");
                return;
            }

            BeginInstallation(installPath);
        }

        private void BeginInstallation(string installPath)
        {
            installationInProgress = true;
            failureLogPath = null;
            primaryButton.Enabled = false;
            primaryButton.Text = "Устанавливаем…";
            closeButton.Enabled = false;
            minimizeButton.Enabled = false;
            pathTextBox.Enabled = false;
            browseButton.Enabled = false;
            detailLabel.Visible = false;
            progressBar.Visible = true;
            progressBar.StartMarquee();
            installStatusLabel.Text = "Подготавливаем защищённый пакет…";
            installStatusLabel.Visible = true;
            primaryButton.Top = 482;

            BackgroundWorker worker = new BackgroundWorker();
            worker.WorkerReportsProgress = true;
            worker.DoWork += delegate(object sender, DoWorkEventArgs arguments)
            {
                BackgroundWorker activeWorker = (BackgroundWorker)sender;
                arguments.Result = InstallPayload(activeWorker, installPath);
            };
            worker.ProgressChanged += delegate(object sender, ProgressChangedEventArgs arguments)
            {
                string message = arguments.UserState as string;
                if (!String.IsNullOrEmpty(message))
                {
                    installStatusLabel.Text = message;
                }
            };
            worker.RunWorkerCompleted += InstallationCompleted;
            worker.RunWorkerAsync();
        }

        private InstallResult InstallPayload(BackgroundWorker worker, string installPath)
        {
            string workingDirectory = Path.Combine(
                Path.GetTempPath(),
                "HoshiraInstaller",
                Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(workingDirectory);

            string msiPath = Path.Combine(workingDirectory, "Hoshira-" + InstallerVersion + ".msi");
            string installLogPath = Path.Combine(workingDirectory, "install.log");
            string uninstallLogPath = Path.Combine(workingDirectory, "uninstall.log");

            try
            {
                worker.ReportProgress(0, "Проверяем системные компоненты…");
                if (!WebView2Runtime.IsInstalled())
                {
                    string webView2BootstrapperPath = Path.Combine(
                        workingDirectory,
                        "MicrosoftEdgeWebview2Setup.exe");
                    worker.ReportProgress(
                        0,
                        "Подготавливаем Microsoft Edge WebView2 Runtime…");
                    ExtractResource(
                        WebView2BootstrapperResourceName,
                        webView2BootstrapperPath,
                        "В установщике отсутствует компонент WebView2 Runtime.");

                    worker.ReportProgress(
                        0,
                        "Устанавливаем Microsoft Edge WebView2 Runtime…");
                    int webView2ExitCode = RunElevatedExecutable(
                        webView2BootstrapperPath,
                        "/silent /install");
                    if (!WebView2Runtime.IsInstalled())
                    {
                        TryDeleteDirectory(workingDirectory);
                        return InstallResult.Failed(
                            webView2ExitCode,
                            null,
                            DescribeWebView2Error(webView2ExitCode));
                    }
                }

                worker.ReportProgress(0, "Распаковываем Hoshira…");
                ExtractPayload(msiPath);

                InstalledProduct currentProduct = InstalledProduct.Find();
                if (currentProduct != null &&
                    CompareVersions(currentProduct.Version, InstallerVersion) == 0 &&
                    !String.IsNullOrEmpty(currentProduct.ProductCode))
                {
                    worker.ReportProgress(0, "Удаляем предыдущую сборку этой версии…");
                    int uninstallExitCode = RunMsi(
                        "/x " + Quote(currentProduct.ProductCode) +
                        " /qn /norestart /L*v " + Quote(uninstallLogPath));
                    if (!IsMsiSuccess(uninstallExitCode))
                    {
                        return InstallResult.Failed(
                            uninstallExitCode,
                            uninstallLogPath,
                            DescribeMsiError(uninstallExitCode));
                    }
                }

                worker.ReportProgress(0, "Устанавливаем Hoshira…");
                int installExitCode = RunMsi(
                    "/i " + Quote(msiPath) +
                    " /qn /norestart INSTALLDIR=" + Quote(installPath) +
                    " /L*v " + Quote(installLogPath));

                if (installExitCode == 1638)
                {
                    currentProduct = InstalledProduct.Find();
                    if (currentProduct != null && !String.IsNullOrEmpty(currentProduct.ProductCode))
                    {
                        worker.ReportProgress(0, "Подготавливаем чистую переустановку…");
                        int uninstallExitCode = RunMsi(
                            "/x " + Quote(currentProduct.ProductCode) +
                            " /qn /norestart /L*v " + Quote(uninstallLogPath));
                        if (!IsMsiSuccess(uninstallExitCode))
                        {
                            return InstallResult.Failed(
                                uninstallExitCode,
                                uninstallLogPath,
                                DescribeMsiError(uninstallExitCode));
                        }

                        installExitCode = RunMsi(
                            "/i " + Quote(msiPath) +
                            " /qn /norestart INSTALLDIR=" + Quote(installPath) +
                            " /L*v " + Quote(installLogPath));
                    }
                }

                if (!IsMsiSuccess(installExitCode))
                {
                    return InstallResult.Failed(
                        installExitCode,
                        installLogPath,
                        DescribeMsiError(installExitCode));
                }

                worker.ReportProgress(0, "Завершаем установку…");
                Thread.Sleep(350);
                TryDeleteDirectory(workingDirectory);
                return InstallResult.Succeeded(installPath);
            }
            catch (Win32Exception exception)
            {
                if (exception.NativeErrorCode == 1223)
                {
                    return InstallResult.Failed(
                        1223,
                        installLogPath,
                        "Запрос прав администратора был отменён.");
                }
                return InstallResult.Failed(
                    exception.NativeErrorCode,
                    installLogPath,
                    exception.Message);
            }
            catch (Exception exception)
            {
                return InstallResult.Failed(-1, installLogPath, exception.Message);
            }
        }

        private static void ExtractPayload(string destinationPath)
        {
            ExtractResource(
                PayloadResourceName,
                destinationPath,
                "В установщике отсутствует пакет Hoshira.");
        }

        private static void ExtractResource(
            string resourceName,
            string destinationPath,
            string missingResourceMessage)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            using (Stream source = assembly.GetManifestResourceStream(resourceName))
            {
                if (source == null)
                {
                    throw new InvalidOperationException(missingResourceMessage);
                }

                using (FileStream destination = new FileStream(
                    destinationPath,
                    FileMode.CreateNew,
                    FileAccess.Write,
                    FileShare.None))
                {
                    source.CopyTo(destination);
                }
            }
        }

        private static int RunMsi(string arguments)
        {
            return RunElevatedExecutable(
                Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.System),
                    "msiexec.exe"),
                arguments);
        }

        private static int RunElevatedExecutable(string fileName, string arguments)
        {
            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = fileName;
            startInfo.Arguments = arguments;
            startInfo.UseShellExecute = true;
            startInfo.Verb = "runas";
            startInfo.WindowStyle = ProcessWindowStyle.Hidden;

            using (Process process = Process.Start(startInfo))
            {
                process.WaitForExit();
                return process.ExitCode;
            }
        }

        private void InstallationCompleted(object sender, RunWorkerCompletedEventArgs eventArgs)
        {
            installationInProgress = false;
            closeButton.Enabled = true;
            minimizeButton.Enabled = true;
            progressBar.StopMarquee();
            progressBar.Visible = false;
            installStatusLabel.Visible = false;

            InstallResult result = eventArgs.Result as InstallResult;
            if (eventArgs.Error != null)
            {
                result = InstallResult.Failed(-1, null, eventArgs.Error.Message);
            }

            if (result != null && result.Success)
            {
                installationComplete = true;
                headingLabel.Text = "Hoshira\nустановлена";
                descriptionLabel.Text =
                    "Всё готово. Можно открыть приложение прямо сейчас.";
                pathPanel.Visible = false;
                detailLabel.Text = "Ярлыки созданы на рабочем столе и в меню «Пуск».";
                detailLabel.Top = 355;
                detailLabel.Visible = true;
                primaryButton.Text = "Запустить Hoshira";
                primaryButton.Top = 421;
                primaryButton.Width = 222;
                primaryButton.Enabled = true;
                secondaryButton.Top = 421;
                secondaryButton.Visible = true;
                return;
            }

            failureLogPath = result == null ? null : result.LogPath;
            headingLabel.Text = "Не удалось\nустановить Hoshira";
            descriptionLabel.Text = result == null
                ? "Произошла неизвестная ошибка."
                : result.Message;
            pathPanel.Visible = false;
            detailLabel.Text = String.IsNullOrEmpty(failureLogPath)
                ? "Попробуйте запустить установщик ещё раз."
                : "Журнал сохранён: " + failureLogPath;
            detailLabel.Top = 360;
            detailLabel.Height = 48;
            detailLabel.Visible = true;
            primaryButton.Text = String.IsNullOrEmpty(failureLogPath)
                ? "Повторить"
                : "Открыть журнал";
            primaryButton.Top = 430;
            primaryButton.Enabled = true;
            secondaryButton.Top = 430;
            secondaryButton.Visible = true;
        }

        private void LaunchInstalledApplication()
        {
            string applicationPath = Path.Combine(pathTextBox.Text, "Hoshira.exe");
            InstalledProduct product = InstalledProduct.Find();
            if (product != null && !String.IsNullOrWhiteSpace(product.InstallLocation))
            {
                applicationPath = Path.Combine(product.InstallLocation, "Hoshira.exe");
            }

            try
            {
                Process.Start(applicationPath);
                Close();
            }
            catch (Exception exception)
            {
                ShowInstallerMessage(
                    "Не удалось запустить Hoshira",
                    exception.Message);
            }
        }

        private void CloseButtonClick(object sender, EventArgs eventArgs)
        {
            if (!installationInProgress)
            {
                Close();
            }
        }

        private void InstallerFormClosing(object sender, FormClosingEventArgs eventArgs)
        {
            if (installationInProgress)
            {
                eventArgs.Cancel = true;
            }
        }

        private void ApplyRoundedRegion()
        {
            using (GraphicsPath path = RoundedGeometry.Create(
                new Rectangle(0, 0, Width, Height),
                22))
            {
                Region = new Region(path);
            }
        }

        private static Label CreateLabel(
            string text,
            int left,
            int top,
            int width,
            int height,
            float size,
            FontStyle style,
            Color color)
        {
            Label label = new Label();
            label.Text = text;
            label.Left = left;
            label.Top = top;
            label.Width = width;
            label.Height = height;
            label.ForeColor = color;
            label.BackColor = Color.Transparent;
            label.Font = new Font("Segoe UI", size, style, GraphicsUnit.Point);
            label.AutoSize = false;
            label.UseCompatibleTextRendering = true;
            return label;
        }

        private static bool IsMsiSuccess(int exitCode)
        {
            return exitCode == 0 || exitCode == 1641 || exitCode == 3010;
        }

        private static string DescribeMsiError(int exitCode)
        {
            switch (exitCode)
            {
                case 1602:
                case 1223:
                    return "Установка была отменена.";
                case 1603:
                    return "Windows Installer не смог завершить установку. " +
                        "Проверьте свободное место и права доступа.";
                case 1618:
                    return "Сейчас выполняется другая установка Windows. " +
                        "Дождитесь её завершения и повторите попытку.";
                case 1638:
                    return "В Windows уже зарегистрирована эта версия Hoshira.";
                default:
                    return "Windows Installer завершился с кодом " + exitCode + ".";
            }
        }

        private static string DescribeWebView2Error(int exitCode)
        {
            switch (exitCode)
            {
                case 1602:
                case 1223:
                    return "Установка Microsoft Edge WebView2 Runtime была отменена.";
                case 3010:
                    return "WebView2 Runtime установлен, но Windows требуется перезагрузка.";
                default:
                    return "Не удалось установить Microsoft Edge WebView2 Runtime. " +
                        "Проверьте подключение к интернету и повторите попытку. " +
                        "Код установщика: " + exitCode + ".";
            }
        }

        private static string Quote(string value)
        {
            return "\"" + value.Replace("\"", "\\\"") + "\"";
        }

        private static int CompareVersions(string left, string right)
        {
            Version leftVersion;
            Version rightVersion;
            if (!Version.TryParse(left, out leftVersion))
            {
                leftVersion = new Version(0, 0);
            }
            if (!Version.TryParse(right, out rightVersion))
            {
                rightVersion = new Version(0, 0);
            }
            return leftVersion.CompareTo(rightVersion);
        }

        private static void TryDeleteDirectory(string path)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    Directory.Delete(path, true);
                }
            }
            catch
            {
                // Temporary installation files can be removed by Windows later.
            }
        }

        private void ShowInstallerMessage(string title, string message)
        {
            MessageBox.Show(
                this,
                message,
                title,
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }

        private void TitleBarMouseDown(object sender, MouseEventArgs eventArgs)
        {
            if (eventArgs.Button == MouseButtons.Left)
            {
                ReleaseCapture();
                SendMessage(Handle, 0xA1, new IntPtr(0x2), IntPtr.Zero);
            }
        }

        [DllImport("user32.dll")]
        private static extern bool ReleaseCapture();

        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(
            IntPtr window,
            int message,
            IntPtr parameter,
            IntPtr lParam);
    }

    internal static class WebView2Runtime
    {
        private const string ClientKeyPath =
            @"Software\Microsoft\EdgeUpdate\Clients\" +
            "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";

        internal static bool IsInstalled()
        {
            return !String.IsNullOrEmpty(FindVersion());
        }

        internal static string FindVersion()
        {
            RegistryHive[] hives = new RegistryHive[]
            {
                RegistryHive.LocalMachine,
                RegistryHive.CurrentUser
            };
            RegistryView[] views = new RegistryView[]
            {
                RegistryView.Registry64,
                RegistryView.Registry32
            };

            foreach (RegistryHive hive in hives)
            {
                foreach (RegistryView view in views)
                {
                    try
                    {
                        using (RegistryKey baseKey = RegistryKey.OpenBaseKey(hive, view))
                        using (RegistryKey clientKey = baseKey.OpenSubKey(ClientKeyPath))
                        {
                            if (clientKey == null)
                            {
                                continue;
                            }

                            string versionText = clientKey.GetValue("pv") as string;
                            Version version;
                            if (!String.IsNullOrWhiteSpace(versionText) &&
                                Version.TryParse(versionText, out version) &&
                                version.CompareTo(new Version(0, 0, 0, 0)) > 0)
                            {
                                return version.ToString();
                            }
                        }
                    }
                    catch
                    {
                        // Continue with the next registry location.
                    }
                }
            }

            return null;
        }
    }

    internal sealed class InstalledProduct
    {
        internal string ProductCode;
        internal string Version;
        internal string InstallLocation;

        internal static InstalledProduct Find()
        {
            RegistryHive[] hives = new RegistryHive[]
            {
                RegistryHive.LocalMachine,
                RegistryHive.CurrentUser
            };
            RegistryView[] views = new RegistryView[]
            {
                RegistryView.Registry64,
                RegistryView.Registry32
            };

            foreach (RegistryHive hive in hives)
            {
                foreach (RegistryView view in views)
                {
                    try
                    {
                        using (RegistryKey baseKey = RegistryKey.OpenBaseKey(hive, view))
                        using (RegistryKey uninstallKey = baseKey.OpenSubKey(
                            @"Software\Microsoft\Windows\CurrentVersion\Uninstall"))
                        {
                            if (uninstallKey == null)
                            {
                                continue;
                            }

                            foreach (string productCode in uninstallKey.GetSubKeyNames())
                            {
                                using (RegistryKey productKey = uninstallKey.OpenSubKey(productCode))
                                {
                                    if (productKey == null)
                                    {
                                        continue;
                                    }

                                    string displayName = productKey.GetValue("DisplayName") as string;
                                    if (!String.Equals(
                                        displayName,
                                        "Hoshira",
                                        StringComparison.OrdinalIgnoreCase))
                                    {
                                        continue;
                                    }

                                    InstalledProduct product = new InstalledProduct();
                                    product.ProductCode = productCode;
                                    product.Version =
                                        productKey.GetValue("DisplayVersion") as string ?? "0.0";
                                    product.InstallLocation =
                                        productKey.GetValue("InstallLocation") as string;
                                    return product;
                                }
                            }
                        }
                    }
                    catch
                    {
                        // Continue with the next registry view.
                    }
                }
            }

            return null;
        }
    }

    internal sealed class InstallResult
    {
        internal bool Success;
        internal string InstallPath;
        internal string LogPath;
        internal string Message;
        internal int ExitCode;

        internal static InstallResult Succeeded(string installPath)
        {
            return new InstallResult
            {
                Success = true,
                InstallPath = installPath
            };
        }

        internal static InstallResult Failed(int exitCode, string logPath, string message)
        {
            return new InstallResult
            {
                Success = false,
                ExitCode = exitCode,
                LogPath = logPath,
                Message = message
            };
        }
    }

    internal sealed class RoundedPanel : Panel
    {
        private readonly Color fillColor;
        private readonly Color borderColor;
        private readonly int radius;

        internal RoundedPanel(Color fill, Color border, int cornerRadius)
        {
            fillColor = fill;
            borderColor = border;
            radius = cornerRadius;
            BackColor = Color.Transparent;
            DoubleBuffered = true;
        }

        protected override void OnPaint(PaintEventArgs eventArgs)
        {
            eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = RoundedGeometry.Create(bounds, radius))
            using (SolidBrush fill = new SolidBrush(fillColor))
            using (Pen border = new Pen(borderColor))
            {
                eventArgs.Graphics.FillPath(fill, path);
                eventArgs.Graphics.DrawPath(border, path);
            }
        }
    }

    internal sealed class RoundedButton : Button
    {
        private readonly Color normalColor;
        private readonly Color hoverColor;
        private readonly Color textColor;
        private readonly Color cornerColor;
        private readonly int radius;
        private bool hovered;

        internal RoundedButton(
            string text,
            Color background,
            Color hover,
            Color foreground,
            int cornerRadius,
            Color cornerBackground)
        {
            Text = text;
            normalColor = background;
            hoverColor = hover;
            textColor = foreground;
            cornerColor = cornerBackground;
            radius = cornerRadius;
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            BackColor = background;
            ForeColor = foreground;
            Cursor = Cursors.Hand;
            TabStop = false;
            UseVisualStyleBackColor = false;
            DoubleBuffered = true;
        }

        protected override void OnMouseEnter(EventArgs eventArgs)
        {
            hovered = true;
            Invalidate();
            base.OnMouseEnter(eventArgs);
        }

        protected override void OnMouseLeave(EventArgs eventArgs)
        {
            hovered = false;
            Invalidate();
            base.OnMouseLeave(eventArgs);
        }

        protected override void OnPaint(PaintEventArgs eventArgs)
        {
            eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            eventArgs.Graphics.Clear(cornerColor);
            Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
            Color fillColor = Enabled
                ? (hovered ? hoverColor : normalColor)
                : Color.FromArgb(44, 45, 50);
            Color currentTextColor = Enabled
                ? textColor
                : Color.FromArgb(112, 114, 121);

            using (GraphicsPath path = RoundedGeometry.Create(bounds, radius))
            using (SolidBrush fill = new SolidBrush(fillColor))
            using (SolidBrush textBrush = new SolidBrush(currentTextColor))
            {
                eventArgs.Graphics.FillPath(fill, path);
                StringFormat format = new StringFormat();
                format.Alignment = StringAlignment.Center;
                format.LineAlignment = StringAlignment.Center;
                eventArgs.Graphics.DrawString(Text, Font, textBrush, bounds, format);
                format.Dispose();
            }
        }
    }

    internal sealed class MarqueeBar : Control
    {
        private readonly System.Windows.Forms.Timer timer;
        private int offset;

        internal MarqueeBar()
        {
            DoubleBuffered = true;
            timer = new System.Windows.Forms.Timer();
            timer.Interval = 16;
            timer.Tick += delegate
            {
                offset = (offset + 7) % Math.Max(1, Width + 150);
                Invalidate();
            };
        }

        internal void StartMarquee()
        {
            offset = 0;
            timer.Start();
        }

        internal void StopMarquee()
        {
            timer.Stop();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                timer.Dispose();
            }
            base.Dispose(disposing);
        }

        protected override void OnPaint(PaintEventArgs eventArgs)
        {
            eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath track = RoundedGeometry.Create(
                new Rectangle(0, 0, Width - 1, Height - 1),
                Height / 2))
            using (SolidBrush trackBrush = new SolidBrush(Color.FromArgb(37, 38, 43)))
            {
                eventArgs.Graphics.FillPath(trackBrush, track);
            }

            int segmentWidth = Math.Min(150, Width / 3);
            int segmentLeft = offset - segmentWidth;
            Rectangle segment = new Rectangle(segmentLeft, 0, segmentWidth, Height);
            using (LinearGradientBrush gradient = new LinearGradientBrush(
                segment,
                Color.FromArgb(80, 255, 78, 0),
                Color.FromArgb(255, 255, 78, 0),
                LinearGradientMode.Horizontal))
            {
                eventArgs.Graphics.FillRectangle(gradient, segment);
            }
        }
    }

    internal sealed class HeroPanel : Panel
    {
        internal HeroPanel()
        {
            BackColor = Color.Transparent;
            DoubleBuffered = true;
        }

        protected override void OnPaint(PaintEventArgs eventArgs)
        {
            Graphics graphics = eventArgs.Graphics;
            graphics.SmoothingMode = SmoothingMode.AntiAlias;

            using (GraphicsPath glowPath = new GraphicsPath())
            {
                glowPath.AddEllipse(28, 46, 390, 390);
                using (PathGradientBrush glow = new PathGradientBrush(glowPath))
                {
                    glow.CenterColor = Color.FromArgb(82, 255, 72, 0);
                    glow.SurroundColors = new Color[] { Color.FromArgb(0, 255, 72, 0) };
                    graphics.FillPath(glow, glowPath);
                }
            }

            using (Pen thinLine = new Pen(Color.FromArgb(38, 255, 255, 255), 1F))
            {
                graphics.DrawEllipse(thinLine, 86, 106, 218, 218);
                graphics.DrawEllipse(thinLine, 65, 85, 260, 260);
            }

            BrandArtwork.DrawGlyph(
                graphics,
                new RectangleF(105, 125, 180, 180),
                Color.FromArgb(205, 255, 255, 255));
        }
    }

    internal sealed class BrandGlyphControl : Control
    {
        internal BrandGlyphControl()
        {
            SetStyle(ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            DoubleBuffered = true;
        }

        protected override void OnPaint(PaintEventArgs eventArgs)
        {
            eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            BrandArtwork.DrawGlyph(
                eventArgs.Graphics,
                new RectangleF(1, 1, Width - 2, Height - 2),
                Color.FromArgb(235, 255, 255, 255));
        }
    }

    internal static class BrandArtwork
    {
        internal static void DrawGlyph(
            Graphics graphics,
            RectangleF bounds,
            Color color)
        {
            float width = bounds.Width;
            float height = bounds.Height;
            float strokeWidth = Math.Min(width, height) * 0.065F;
            float markStrokeWidth = strokeWidth * 1.22F;

            using (GraphicsPath outline = new GraphicsPath())
            {
                outline.StartFigure();
                outline.AddLine(Point(bounds, 0.50F, 0.05F), Point(bounds, 0.84F, 0.24F));
                outline.AddLine(Point(bounds, 0.84F, 0.24F), Point(bounds, 0.84F, 0.76F));
                outline.AddLine(Point(bounds, 0.84F, 0.76F), Point(bounds, 0.50F, 0.95F));
                outline.AddLine(Point(bounds, 0.50F, 0.95F), Point(bounds, 0.16F, 0.76F));
                outline.AddLine(Point(bounds, 0.16F, 0.76F), Point(bounds, 0.16F, 0.24F));
                outline.CloseFigure();

                using (Pen outlinePen = CreatePen(color, strokeWidth))
                {
                    graphics.DrawPath(outlinePen, outline);
                }
            }

            using (Pen markPen = CreatePen(Color.FromArgb(255, color.R, color.G, color.B), markStrokeWidth))
            {
                graphics.DrawLine(markPen, Point(bounds, 0.36F, 0.29F), Point(bounds, 0.30F, 0.72F));
                graphics.DrawLine(markPen, Point(bounds, 0.70F, 0.28F), Point(bounds, 0.64F, 0.71F));
                graphics.DrawLine(markPen, Point(bounds, 0.33F, 0.52F), Point(bounds, 0.67F, 0.48F));
            }
        }

        private static Pen CreatePen(Color color, float width)
        {
            Pen pen = new Pen(color, width);
            pen.StartCap = LineCap.Round;
            pen.EndCap = LineCap.Round;
            pen.LineJoin = LineJoin.Round;
            return pen;
        }

        private static PointF Point(RectangleF bounds, float x, float y)
        {
            return new PointF(
                bounds.Left + bounds.Width * x,
                bounds.Top + bounds.Height * y);
        }
    }

    internal static class RoundedGeometry
    {
        internal static GraphicsPath Create(Rectangle bounds, int radius)
        {
            int diameter = Math.Max(1, radius * 2);
            GraphicsPath path = new GraphicsPath();
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
            path.AddArc(
                bounds.Right - diameter,
                bounds.Bottom - diameter,
                diameter,
                diameter,
                0,
                90);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }
    }
}
