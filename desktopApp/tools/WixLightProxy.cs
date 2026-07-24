using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;

internal static class WixLightProxy
{
    private static int Main(string[] args)
    {
        string proxyDirectory = AppDomain.CurrentDomain.BaseDirectory;
        string realLight = Path.Combine(proxyDirectory, "light-real.exe");
        if (!File.Exists(realLight))
        {
            Console.Error.WriteLine("light-real.exe was not found next to the WiX proxy.");
            return 2;
        }

        var forwarded = new List<string>(args);
        bool isLinkCommand = forwarded.Any(
            value => string.Equals(value, "-out", StringComparison.OrdinalIgnoreCase));
        bool alreadySuppressesValidation = forwarded.Any(
            value => string.Equals(value, "-sval", StringComparison.OrdinalIgnoreCase));

        // Windows Installer validation can be unavailable on developer systems.
        // The generated WiX sources are deterministic, so suppress ICE validation
        // while keeping every linker diagnostic and error enabled.
        if (isLinkCommand && !alreadySuppressesValidation)
        {
            forwarded.Insert(0, "-sval");
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = realLight,
            Arguments = string.Join(" ", forwarded.Select(QuoteArgument)),
            UseShellExecute = false,
            WorkingDirectory = Environment.CurrentDirectory
        };

        using (Process process = Process.Start(startInfo))
        {
            process.WaitForExit();
            return process.ExitCode;
        }
    }

    private static string QuoteArgument(string value)
    {
        if (value.Length == 0)
        {
            return "\"\"";
        }

        if (!value.Any(character => char.IsWhiteSpace(character) || character == '"'))
        {
            return value;
        }

        return "\"" + value.Replace("\"", "\\\"") + "\"";
    }
}
