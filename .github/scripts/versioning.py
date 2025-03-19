import argparse
import json
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Tuple, Optional
from xml.etree import ElementTree as ET


def get_platform_path(cwd: Path, os_name: str) -> Path:
  """
  Returns the path to the compressed platform artifact based on the OS.

  Args:
      cwd: The current working directory (where the artifacts are expected).
      os_name: The operating system name ("windows", "linux", or "darwin").

  Returns:
      The Path object representing the compressed platform artifact.

  Raises:
      ValueError: If the os_name is not supported.
  """
  if os_name == "windows":
    return cwd / "sherlock-platform.win.zip"
  elif os_name == "linux":
    return cwd / "sherlock-platform.tar.gz"
  elif os_name == "darwin":  # macOS
    return cwd / "sherlock-platform.mac.aarch64.zip"
  else:
    raise ValueError(f"Unsupported OS: {os_name}")


def get_extracted_platform_path(os_name: str) -> str:
  """
  Returns the expected name of the directory after extracting the platform artifact.

  Args:
      os_name: The operating system name ("windows", "linux", or "darwin").

  Returns:
      The name of the extracted platform directory.

  Raises:
      ValueError: If the os_name is not supported.
  """
  if os_name == "windows":
    return "sherlock-platform.win"
  elif os_name == "linux":
    return "sherlock-platform"
  elif os_name == "darwin":  # macOS
    return "sherlock-platform.mac.aarch64"
  else:
    raise ValueError(f"Unsupported OS: {os_name}")


def get_app_jar_path(artifact_path: Path, os_name: str) -> Path:
  """
  Returns the path to the 'lib' directory containing app.jar within the extracted platform.

  Args:
      artifact_path: The path to the extracted platform directory.
      os_name: The operating system name ("windows", "linux", or "darwin").

  Returns:
      The Path object representing the 'lib' directory.

  Raises:
      ValueError: If the os_name is not supported.
  """
  if os_name == "windows":
    return artifact_path / "lib"
  elif os_name == "linux":
    # TODO: update "Sherlock-2024.2.1" to Sherlock
    return artifact_path / "Sherlock-2024.2.1" / "lib"
  elif os_name == "darwin":  # macOS
    return artifact_path / "Sherlock.app" / "Contents" / "lib"
  else:
    raise ValueError(f"Unsupported OS: {os_name}")


def extract_jar(artifact_path: Path, extracted_jar_path: Path) -> None:
  """
  Extracts the app.jar from the platform's 'lib' directory.

  Args:
      artifact_path: The path to the extracted platform directory.
      extracted_jar_path: The directory where the contents of app.jar should be extracted.
  """
  print(f"Extracting {artifact_path}/app.jar to {extracted_jar_path}...")
  try:
    extracted_jar_path.mkdir(exist_ok=True)
    # NOTE: This extraction will create a nested directory when run on Mac, leading to a failure of the script.
    # We can handle it but it seems unnecessary as the github runners will only run on linux.
    subprocess.run(["jar", "-xf", str(artifact_path / "app.jar")], check=True, capture_output=True, text=True, cwd=extracted_jar_path)
    print(f"Extracted JAR successfully")
  except zipfile.BadZipFile:
    print("Error: app.jar is not a valid JAR directory.")
  except Exception as e:
    print(f"Error extracting JAR: {e}")
    return


def extract_platform(cwd: Path, os_name: str) -> None:
  """
  Extracts the platform artifact to a directory named after the platform.

  Args:
      cwd: The directory containing the compressed platform artifact.
      os_name: The operating system name ("windows", "linux", or "darwin").

  Raises:
      ValueError: If the OS is not supported.
      RuntimeError: If platform extraction fails.
  """
  artifact_path = get_platform_path(cwd, os_name)
  extracted_platform = cwd / get_extracted_platform_path(os_name)
  print(f"Extracting Platform at: {artifact_path}...")
  try:
    extracted_platform.mkdir(parents=True, exist_ok=True)
    if os_name == "linux":
      subprocess.run(["tar", "-xzf", artifact_path, "-C", extracted_platform, "--strip-components=1"], check=True,
                     capture_output=True, text=True, cwd=artifact_path.parent)
    else:
      subprocess.run(["unzip", artifact_path, "-d", extracted_platform], check=True, capture_output=True, text=True,
                     cwd=artifact_path.parent)
    print("Platform extracted successfully.")
  except (tarfile.TarError, zipfile.BadZipFile, OSError) as e:
    extracted_platform.unlink(missing_ok=True)
    print(f"Error extracting platform: {e}.")


def _parse_version(version_tag: str, prerelease: str) -> Tuple[str, str, Optional[str], Optional[str]]:
  """
  Parses a version tag string and returns its components, validating against the prerelease flag.

  Args:
      version_tag: The version tag string (e.g., "v1.2", "v1.2.3", "v1.2.3.4-dev").
      prerelease: "true" if it's a prerelease, "false" otherwise.

  Returns:
      A tuple containing the major, minor, patch (or None) and build_number (or None).

  Raises:
      ValueError: If the version tag format is invalid or doesn't match the prerelease flag.
  """
  print(f"Parsing version tag..{version_tag}")
  pattern = r"^v?(\d+)\.(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-dev)?$"
  match = re.match(pattern, version_tag)
  if not match:
    raise ValueError(f"Invalid version tag format: {version_tag}")

  major, minor, patch, build_number = match.groups()

  if prerelease == "true" and build_number is None:
    raise ValueError(f"Prerelease flag set to true, but no build_number number found: {version_tag}")
  elif prerelease == "false" and build_number is not None:
    raise ValueError(f"Prerelease flag set to false, but build_number number found: {version_tag}")

  print(f"Version: major: {major}, minor: {minor}, patch: {patch}, build_number: {build_number}")
  return major, minor, patch, build_number


def remove_stale_artifacts(artifact_path):
  """
  Removes a file or directory, ensuring necessary permissions.

  Args:
      artifact_path: The path to the file or directory to remove.
  """
  # Permission to delete the directory.
  try:
    if artifact_path.is_dir():
      subprocess.run(['chmod', '-R', '777', str(artifact_path)], check=True)
    elif artifact_path.is_file():
      subprocess.run(['chmod', '777', str(artifact_path)], check=True)
  except subprocess.CalledProcessError as chmod_error:
    print(f"Failed to change permissions: {chmod_error}")

  try:
    if artifact_path.is_file():
      artifact_path.unlink()
      print(f"Deleted file: {artifact_path}")
    elif artifact_path.is_dir():
      shutil.rmtree(artifact_path)
      print(f"Deleted directory: {artifact_path}")
    else:
      print(f"Path is not a file or directory: {artifact_path}")
  except OSError as e:
    # Suppress potential deletion errors.
    print(f"Error deleting artifact {artifact_path}: {e}")


def compress_platform(artifact_path: Path, os_name: str):
  """
  Compresses the platform directory into a zip or tar.gz archive.

  Args:
      artifact_path: The path to the platform directory to compress.
      os_name: The operating system name ("windows", "linux", or "darwin").

  Raises:
      RuntimeError: If compression fails.
  """
  print(f"Compressing the updated platform artifact at {artifact_path}")
  try:
    if os_name == "darwin" or os_name == "windows":
      subprocess.run(["zip", "-r", artifact_path.with_suffix(".zip"), artifact_path.name], cwd=artifact_path.parent, check=True,
                     capture_output=True, text=True)
    else:
      subprocess.run(["tar", "-czf", artifact_path.with_suffix(".tar.gz"), "-C", artifact_path.parent, artifact_path.name], check=True,
                     capture_output=True, text=True)
    print("Compressed successfully.")

  except subprocess.CalledProcessError as e:
    print(f"Error compressing: {e}")
    return


def compress_jar(extracted_jar_path: Path):
  """
  Creates a JAR archive named 'app.jar' from the contents of the specified directory.

  Args:
      extracted_jar_path: The path to the directory containing the extracted app contents.

  Raises:
      RuntimeError: If JAR creation fails.
  """
  print(f"Creating JAR from {extracted_jar_path}...")
  output_jar_path = extracted_jar_path.parent / "app.jar"
  try:
    # Navigate to the parent directory of the source directory
    subprocess.run(
      ["jar", "-cf", output_jar_path, "."],
      check=True,
      capture_output=True,
      text=True,
      cwd=extracted_jar_path
    )
    print(f"JAR created successfully at {output_jar_path}.")

  except subprocess.CalledProcessError as e:
    print(f"Error creating JAR: {e}")


def _format_build_date() -> str:
  """Returns the current date formatted as YYYYMMDD."""
  return datetime.now().strftime("%Y%m%d")


def _patch_app_info(directory_path: Path, version: str, prerelease: str) -> None:
  """
  Updates the SherlockPlatformApplicationInfo.xml version and build date.

  Args:
      directory_path: Path to the directory containing the 'idea' folder.
      version_tag: The full version tag.
      prerelease: "true" if it's a prerelease, "false" otherwise.
  """
  xml_file_path = directory_path / "idea" / "SherlockPlatformApplicationInfo.xml"
  namespace = {'appinfo': 'http://jetbrains.org/intellij/schema/application-info'}
  build_date = _format_build_date()
  major, minor, patch, build_number = _parse_version(version, prerelease)

  try:
    tree = ET.parse(xml_file_path)
    root = tree.getroot()

    version_element = root.find('appinfo:version', namespace)
    if version_element is not None:
      version_element.set('major', major)
      version_element.set('minor', minor)
      if patch is not None:
        patch_value = patch
        if build_number is not None:
          patch_value += f".{build_number}-dev"
        version_element.set('patch', patch_value)
      print(f"Updated version in {xml_file_path}")
    else:
      print(f"Warning: 'version' element not found in {xml_file_path}")

    build_element = root.find('appinfo:build', namespace)
    if build_element is not None:
      build_element.set('date', build_date)
      print(f"Updated build date in {xml_file_path}")
    else:
      print(f"Warning: 'build' element not found in {xml_file_path}")

    # Write the updated XML back to the file
    tree.write(xml_file_path)
    print(f"Successfully updated {xml_file_path}")

  except ET.ParseError as e:
    print(f"Warning: Could not parse XML {xml_file_path}: {e}")
  except FileNotFoundError:
    print(f"Warning: XML file not found at {xml_file_path}")
  except Exception as e:
    print(f"An unexpected error occurred: {e}")


def _patch_product_info(directory_path: Path, version: str) -> None:
  """
  Updates the product-info.json version.

  Args:
      directory_path: Path to the directory containing the 'product-info.json' file.
      version_tag: The full version tag.
  """
  json_file_path = directory_path / "product-info.json"
  try:
    with open(json_file_path, 'r+') as f:
      data = json.load(f)
      data["version"] = version
      f.seek(0)
      json.dump(data, f, indent=2)
      f.truncate()
      print(f"Successfully updated version in {json_file_path}")

  except FileNotFoundError:
    print(f"Warning: JSON file not found at {json_file_path}")
  except json.JSONDecodeError as e:
    print(f"Warning: Could not decode JSON from {json_file_path}: {e}")
  except Exception as e:
    print(f"An unexpected error occurred while updating product info: {e}")


def patch_version(directory_path: Path, version: str, prerelease: str) -> None:
  """
  Patches the version information in app info and product info.

  Args:
      extracted_platform_path: Path to the extracted platform directory.
      version_tag: The full release version tag.
      prerelease: "true" if it's a prerelease, "false" otherwise.
  """
  _patch_app_info(directory_path / "app", version, prerelease)
  _patch_product_info(directory_path.parent, version)


def parse_arguments():
  """Parses command-line arguments."""
  parser = argparse.ArgumentParser(description="Update Sherlock Platform Version Info")
  parser.add_argument("artifacts-dir", help="Directory containing the platform build artifacts")
  parser.add_argument("--release-tag", help="The release tag (e.g., v0.1.0)")
  parser.add_argument("--prerelease", help="If this is a pre-release (e.g. v0.1.0.45-dev")
  return parser.parse_args()


def main():
  """
  Main function to handle command-line arguments and orchestrate the
  extraction, versioning, and compression process for Sherlock platform artifacts.
  """
  args = parse_arguments()
  platforms = ["windows", "linux", "darwin"]
  built_platform_dir = Path(args.artifacts_dir)
  try:
    for plat in platforms:
      plat_path = get_platform_path(built_platform_dir, plat)
      extracted_plat_path = built_platform_dir / get_extracted_platform_path(plat)
      # Extract platform
      extract_platform(built_platform_dir, plat)
      # Extract app jar
      jar_path = get_app_jar_path(extracted_plat_path, plat)
      extracted_jar_path = jar_path / "app"
      extract_jar(jar_path, extracted_jar_path)
      # Update the Version within the tool
      patch_version(jar_path, args.release_tag, args.prerelease)
      remove_stale_artifacts(jar_path / "app.jar")
      compress_jar(extracted_jar_path)
      remove_stale_artifacts(extracted_jar_path)
      remove_stale_artifacts(plat_path)
      compress_platform(extracted_plat_path, plat)
      remove_stale_artifacts(extracted_plat_path)

  except (ValueError, subprocess.CalledProcessError) as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
  except Exception as e:
    print(f"An unexpected error occurred: {e}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
  main()
