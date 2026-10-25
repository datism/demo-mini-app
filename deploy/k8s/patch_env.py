import re

with open('k8s-deployment.yaml', 'r', encoding='utf-8') as f:
    content = f.read()

hep_vars = """
        - name: ENABLE_HEP_SELF_LOGGING
          value: "true"
        - name: HEP_SERVER_HOST
          value: "heplify-server"
        - name: HEP_SERVER_PORT
          value: "9060"
"""

content = re.sub(r'(\s+)env:', r'\1env:' + hep_vars, content)

with open('k8s-deployment.yaml', 'w', encoding='utf-8') as f:
    f.write(content)
