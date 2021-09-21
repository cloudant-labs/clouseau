import logging

from rich.logging import RichHandler

logger = logging.getLogger(__name__)

shell_handler = RichHandler()

logger.setLevel(logging.DEBUG)
shell_handler.setLevel(logging.DEBUG)

fmt_shell = '%(message)s'
shell_formatter = logging.Formatter(fmt_shell)
shell_handler.setFormatter(shell_formatter)

logger.addHandler(shell_handler)
