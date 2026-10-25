with open('k8s-deployment.yaml', 'r', encoding='utf-8') as f:
    content = f.read()

bad_str = """        - name: ENABLE_HEP_SELF_LOGGING
          value: "true"
        - name: HEP_SERVER_HOST
          value: "heplify-server"
        - name: HEP_SERVER_PORT
          value: "9060\""""

good_str = """            - name: ENABLE_HEP_SELF_LOGGING
              value: "true"
            - name: HEP_SERVER_HOST
              value: "heplify-server"
            - name: HEP_SERVER_PORT
              value: "9060\""""

content = content.replace(bad_str, good_str)

with open('k8s-deployment.yaml', 'w', encoding='utf-8') as f:
    f.write(content)
