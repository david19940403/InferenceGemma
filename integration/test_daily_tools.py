import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('daily_tools',Path(__file__).resolve().parents[1]/'remote-scripts/windows/daily_tools.py')
daily=importlib.util.module_from_spec(spec);spec.loader.exec_module(daily)

class DailyToolTests(unittest.TestCase):
    def test_rejects_unknown_actions_paths_and_extra_subjects(self):
        for action,subject in [('delete',''),('git','../private'),('imagenes','C:\\'),('vpn','extra'),('n8n','unknown')]:
            with self.assertRaises(daily.ToolError):daily.dispatch(action,subject)
    def test_workflow_validation_counts_dangling_and_duplicate_nodes(self):
        data={'nodes':[{'name':'A'},{'name':'A','disabled':True}], 'connections':{'A':{'main':[[{'node':'missing'}]]}}}
        summary=daily.validate_workflow(data)
        self.assertEqual(summary,{'nodes':2,'disabled':1,'duplicate_names':1,'dangling_links':1})
        with self.assertRaises(daily.ToolError):daily.validate_workflow({'nodes':[],'connections':{'x':[]}})
    def test_workflow_output_does_not_copy_credentials_queries_or_urls(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'workflow.json'
            file.write_text(json.dumps({'nodes':[{'name':'A','parameters':{'query':'private SQL'},'credentials':{'private':'hidden value'}}],'connections':{}}))
            with patch.object(daily,'configured',return_value=file):summary,details=daily.n8n_audit('aperturas_takepos')
            self.assertEqual(summary['nodes'],1);self.assertIsNone(details)
            self.assertNotIn('private',json.dumps(summary));self.assertNotIn('hidden',json.dumps(summary))
    def test_git_reports_counts_without_paths_or_remote_urls(self):
        def command(args,**kwargs):
            if 'status' in args:return 0,' M app.py\nA  new.py\n?? folder/\n'
            return 0,'feature\n'
        with patch.object(daily,'configured',return_value=Path('.')),patch.object(daily,'run',side_effect=command):
            summary,_=daily.git_status('scanflex')
        self.assertEqual(summary['modified'],1);self.assertEqual(summary['staged'],1);self.assertEqual(summary['untracked_entries'],1)
        self.assertNotIn('app.py',json.dumps(summary))
    def test_image_audit_preserves_input_and_reports_invalid_files(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as folder:
            good=Path(folder)/'product.png';Image.new('RGB',(800,600)).save(good);before=good.read_bytes()
            (Path(folder)/'bad.jpg').write_bytes(b'not an image')
            with patch.object(daily,'configured',return_value=Path(folder)):
                summary,details=daily.image_audit('productos')
            self.assertEqual(summary['checked'],2);self.assertEqual(summary['unreadable'],1);self.assertEqual(good.read_bytes(),before)

if __name__=='__main__':unittest.main()
