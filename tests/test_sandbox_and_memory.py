from pathlib import Path
import pytest
import tempfile
import json
import shutil

from repo_context import ProjectBrain, build_evidence_pack
from sandbox_verifier import SandboxVerifier
from repair_memory import RepairMemory, AutonomyPolicy, AutonomyLevel


class TestProjectBrainAndContextReceipt:
    def test_project_brain_indexes_symbols_and_tests(self, tmp_path):
        service = tmp_path / "auth_service.py"
        service.write_text("from db import get_user\n\ndef get_token():\n    return get_user().token\n", encoding="utf-8")
        
        db_file = tmp_path / "db.py"
        db_file.write_text("class User:\n    pass\n\ndef get_user():\n    return User()\n", encoding="utf-8")

        test_file = tmp_path / "test_auth.py"
        test_file.write_text("def test_token():\n    assert True\n", encoding="utf-8")

        brain = ProjectBrain.build(tmp_path)
        assert brain.source_files_count >= 2
        assert "get_token" in brain.symbols
        assert "get_user" in brain.symbols
        assert "test_auth.py" in brain.tests_discovered

        # Build evidence pack
        error_text = "AttributeError: 'NoneType' object has no attribute 'token' in auth_service.py"
        evidence = build_evidence_pack(brain, error_text, "auth_service.py", 3, token_budget=500)
        assert evidence.receipt is not None
        assert evidence.receipt.total_files >= 1
        assert "get_user" in evidence.allowed_symbols
        assert len(evidence.receipt.items[0].reasons) >= 1


class TestSandboxVerifier:
    def test_sandbox_verification_passes_valid_patch(self, tmp_path):
        code_file = tmp_path / "calc.py"
        code_file.write_text("def add(a, b):\n    return a - b\n\nif __name__ == '__main__':\n    assert add(2, 3) == 5\n", encoding="utf-8")
        
        proof, trust = SandboxVerifier.verify_patch(
            project_root=tmp_path,
            command="python calc.py",
            patch_type="single_line",
            target_file="calc.py",
            line_num=2,
            repair_code="return a + b",
            allowed_symbols={"a", "b", "add"},
        )

        assert proof.sandbox_passed is True
        assert proof.exit_code == 0
        assert trust.total_score >= 85
        assert trust.trust_level == "HIGH"

    def test_sandbox_verification_fails_broken_patch(self, tmp_path):
        code_file = tmp_path / "calc.py"
        code_file.write_text("def add(a, b):\n    return a - b\n\nif __name__ == '__main__':\n    assert add(2, 3) == 5\n", encoding="utf-8")
        
        proof, trust = SandboxVerifier.verify_patch(
            project_root=tmp_path,
            command="python calc.py",
            patch_type="single_line",
            target_file="calc.py",
            line_num=2,
            repair_code="return a * 999",
            allowed_symbols={"a", "b", "add"},
        )

        assert proof.sandbox_passed is False
        assert proof.exit_code != 0
        assert trust.sandbox_pass_score == 0
        assert trust.trust_level in ("LOW", "MEDIUM")

    def test_sandbox_verification_handles_infinite_loop_timeout(self, tmp_path):
        loop_file = tmp_path / "hang.py"
        loop_file.write_text("import time\nwhile True:\n    time.sleep(0.1)\n", encoding="utf-8")

        proof, trust = SandboxVerifier.verify_patch(
            project_root=tmp_path,
            command="python hang.py",
            patch_type="single_line",
            target_file="hang.py",
            line_num=1,
            repair_code="import time",
            timeout_seconds=2,
        )

        assert proof.sandbox_passed is False
        assert proof.exit_code == 124
        assert "timed out" in proof.sandbox_stderr.lower()


class TestRepairMemoryAndPolicy:
    def test_autonomy_policy_decisions(self):
        pol_suggest = AutonomyPolicy(level=AutonomyLevel.SUGGEST_ONLY)
        assert pol_suggest.should_auto_apply(trust_score=100, sandbox_passed=True) is False

        pol_low_risk = AutonomyPolicy(level=AutonomyLevel.AUTO_APPLY_VERIFIED_LOW_RISK)
        assert pol_low_risk.should_auto_apply(trust_score=90, sandbox_passed=True) is True
        assert pol_low_risk.should_auto_apply(trust_score=70, sandbox_passed=True) is False
        assert pol_low_risk.should_auto_apply(trust_score=95, sandbox_passed=False) is False

        pol_auto = AutonomyPolicy(level=AutonomyLevel.FULL_AUTONOMOUS)
        assert pol_auto.should_auto_apply(trust_score=50, sandbox_passed=False) is True

    def test_repair_memory_logging_and_replay(self, tmp_path):
        mem = RepairMemory(tmp_path)
        mem.log_incident(
            incident_id="inc_test_123",
            command="python test.py",
            error_file="service.py",
            error_line=10,
            error_text="ValueError: bad input",
            context_receipt={"total_tokens": 120, "items": []},
            candidate_patch={"repair_code": "return True"},
            repair_proof={"sandbox_passed": True, "exit_code": 0},
            trust_breakdown={"total_score": 95, "trust_level": "HIGH"},
            status="SOLVED"
        )

        incident = mem.get_incident_by_id("inc_test_123")
        assert incident is not None
        assert incident["status"] == "SOLVED"
        assert incident["trust_breakdown"]["total_score"] == 95

        mem.save_verified_repair(
            incident_id="inc_test_123",
            error_type="python",
            file_path="service.py",
            original_line="return False",
            fix_code="return True",
            diff_text=None,
            trust_score=95,
        )

        verified = mem.get_verified_repairs()
        assert len(verified) == 1
        assert verified[0]["fix_code"] == "return True"
